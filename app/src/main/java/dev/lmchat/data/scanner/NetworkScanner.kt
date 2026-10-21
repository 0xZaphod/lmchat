package dev.lmchat.data.scanner

import dev.lmchat.data.remote.JSON
import dev.lmchat.data.remote.ModelList
import dev.lmchat.data.remote.modelsUrl
import dev.lmchat.domain.DiscoveredServer
import dev.lmchat.domain.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans the local subnet for hosts running an OpenAI-compatible model server.
 *
 * Strategy:
 *  1. Derive candidate subnet prefixes from the device's IPv4 interfaces
 *     (e.g. wifi on 192.168.1.42/24 -> "192.168.1.").
 *  2. Concurrently TCP-probe each candidate IP on the target port.
 *  3. For each open port, fetch /v1/models to confirm it is an
 *     OpenAI-compatible server (which LM Studio is) and grab its models.
 */
class NetworkScanner(private val scannerClient: OkHttpClient) {

    data class Candidate(
        val prefix: String,
        val source: String,
    )

    /**
     * Subnet prefixes derived from the device's network interfaces.
     * Falls back to common private ranges when nothing concrete is found.
     */
    fun candidatePrefixes(): List<Candidate> {
        val found = mutableMapOf<String, String>()
        try {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr !is Inet4Address || !addr.isSiteLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    val prefixLen = ia.networkPrefixLength.toInt()
                    if (prefixLen < 16 || prefixLen > 24) continue
                    val mask = (0xFFFFFFFF shl (32 - prefixLen)) and 0xFFFFFFFF
                    val net = ipToLong(ip) and mask
                    val netStr = longToIp(net)
                    // The simple dotted prefix represents one /24. For wider
                    // subnets retain the mask so scan() can enumerate every
                    // address (e.g. 192.168.68.0/22).
                    val prefix = if (prefixLen == 24) {
                        netStr.substringBeforeLast('.') + "."
                    } else {
                        "$netStr/$prefixLen"
                    }
                    found.getOrPut(prefix) { iface.name }
                }
            }
        } catch (_: Exception) {
            // Fall through to defaults below.
        }
        if (found.isEmpty()) {
            found["192.168.1."] = "default"
            found["192.168.0."] = "default"
            found["10.0.0."] = "default"
        }
        return found.map { (prefix, source) -> Candidate(prefix, source) }
    }

    /**
     * Run a scan across the given prefixes for [port].
     * Emits a [DiscoveredServer] for every open port as soon as it is found,
     * then completes. Cancelling the collector cancels the scan.
     */
    fun scan(prefixes: List<String>, port: Int, onProgress: (Int, Int) -> Unit): Flow<DiscoveredServer> =
        channelFlow {
            // Always probe loopback (covers `adb reverse` tunnels) and the
            // emulator host gateway (10.0.2.2 maps to the host machine's
            // 127.0.0.1 in the standard AVD NAT configuration).
            val ips = (prefixes.flatMap(::addressesForPrefix) + EMULATOR_HOST_IPS).distinct()
            val total = ips.size
            val done = AtomicInteger(0)
            val work = Channel<String>(capacity = SCAN_CONCURRENCY)

            coroutineScope {
                val workers = List(minOf(SCAN_CONCURRENCY, ips.size)) {
                    launch(Dispatchers.IO) {
                        for (ip in work) {
                            if (isPortOpen(ip, port)) {
                                send(probeServer(ServerInfo(ip, port)))
                            }
                            onProgress(done.incrementAndGet(), total)
                        }
                    }
                }
                ips.forEach { work.send(it) }
                work.close()
                workers.joinAll()
            }
        }

    private fun addressesForPrefix(prefix: String): List<String> {
        if ('/' !in prefix) return (1..254).map { "$prefix$it" }

        val (networkText, prefixText) = prefix.split('/', limit = 2)
        val prefixLength = prefixText.toIntOrNull()?.takeIf { it in 16..32 } ?: return emptyList()
        val address = runCatching { ipToLong(networkText) }.getOrNull() ?: return emptyList()
        val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        val network = address and mask
        val addressCount = 1L shl (32 - prefixLength)
        val hosts = if (prefixLength >= 31) {
            network until network + addressCount
        } else {
            network + 1 until network + addressCount - 1
        }
        return hosts.map(::longToIp)
    }

    private fun probeServer(server: ServerInfo): DiscoveredServer = try {
        val request = Request.Builder()
            .url(server.modelsUrl())
            .get()
            .build()
        scannerClient.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                val modelList = try {
                    require(JSON.parseToJsonElement(body).jsonObject["data"] is JsonArray)
                    JSON.decodeFromString(ModelList.serializer(), body).data.map { it.id }
                } catch (_: Exception) {
                    null
                }
                DiscoveredServer(
                    host = server.host,
                    port = server.port,
                    isCompatible = modelList != null,
                    models = modelList.orEmpty(),
                )
            } else {
                DiscoveredServer(server.host, server.port, isCompatible = false)
            }
        }
    } catch (_: Exception) {
        DiscoveredServer(server.host, server.port, isCompatible = false)
    }

    companion object {
        const val SCAN_CONCURRENCY = 64
        const val CONNECT_TIMEOUT_MS = 400

        /**
         * Hosts that should always be probed in addition to the subnet sweep:
         * loopback (adb reverse tunnels) and the AVD NAT host gateway.
         */
        val EMULATOR_HOST_IPS = setOf("127.0.0.1", "10.0.2.2")

        fun isPortOpen(host: String, port: Int): Boolean = try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }

        private fun ipToLong(ip: String): Long {
            val parts = ip.split('.')
            return ((parts[0].toLong() and 0xFF) shl 24) or
                ((parts[1].toLong() and 0xFF) shl 16) or
                ((parts[2].toLong() and 0xFF) shl 8) or
                (parts[3].toLong() and 0xFF)
        }

        private fun longToIp(value: Long): String {
            val a = ((value shr 24) and 0xFF).toInt()
            val b = ((value shr 16) and 0xFF).toInt()
            val c = ((value shr 8) and 0xFF).toInt()
            val d = (value and 0xFF).toInt()
            return "$a.$b.$c.$d"
        }
    }
}
