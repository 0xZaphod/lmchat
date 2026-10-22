package dev.lmchat.data.scanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

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
 * Scans local IPv4 subnets for compatible LLM servers.
 *
 * Strategy:
 *  1. Derive candidate subnet prefixes from the device's IPv4 interfaces
 *     (e.g. wifi on 192.168.1.42/24 -> "192.168.1.").
 *  2. Concurrently TCP-probe each candidate IP on the target port.
 *  3. For each open port, fetch /v1/models to confirm it is an
 *     OpenAI-compatible server (which LM Studio is) and grab its models.
 */
class NetworkScanner(private val scannerClient: OkHttpClient, private val context: Context? = null) {

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
        // Android's link properties provide a second source when interface
        // addresses or netmasks are incomplete on a particular device.
        try {
            val connectivity = context?.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val networks = connectivity?.allNetworks.orEmpty()
            for (network in networks) {
                val capabilities = connectivity?.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val properties = connectivity.getLinkProperties(network) ?: continue
                for (link in properties.linkAddresses) {
                    val address = link.address
                    if (address !is Inet4Address || !address.isSiteLocalAddress) continue
                    val prefix = subnetPrefix(address.hostAddress ?: continue, link.prefixLength) ?: continue
                    found.putIfAbsent(prefix, properties.interfaceName ?: "network")
                }
            }
        } catch (_: Exception) {
            // Interface discovery below remains available if Android denies access.
        }
        try {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr !is Inet4Address || !addr.isSiteLocalAddress) continue
                    val prefix = subnetPrefix(addr.hostAddress ?: continue, ia.networkPrefixLength.toInt()) ?: continue
                    found.putIfAbsent(prefix, iface.name)
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
        const val CONNECT_TIMEOUT_MS = 1000

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

        internal fun subnetPrefix(ip: String, prefixLength: Int): String? {
            if (prefixLength !in 16..32) return null
            val octets = ip.split('.')
            if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return null
            val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
            val network = longToIp(ipToLong(ip) and mask)
            return if (prefixLength == 24) network.substringBeforeLast('.') + "."
                else "$network/$prefixLength"
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
