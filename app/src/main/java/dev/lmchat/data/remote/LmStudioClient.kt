package dev.lmchat.data.remote

import dev.lmchat.domain.ServerInfo
import dev.lmchat.domain.ServerProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources
import okio.Timeout
import java.util.concurrent.TimeUnit
import java.io.IOException

/**
 * Shared OpenAI-compatible chat client for local inference servers.
 *
 * Supported servers expose:
 *   GET  /v1/models
 *   POST /v1/chat/completions  (supports `stream: true` as SSE `data:` lines)
 */
class LmStudioClient(private val client: OkHttpClient) {

    private val metadataReader = ModelMetadataReader(client)

    suspend fun modelMetadata(server: ServerInfo, modelId: String) = metadataReader.read(server, modelId)

    /**
     * Probe a server for a valid OpenAI-compatible model list.
     */
    suspend fun probe(server: ServerInfo): ServerProbeResult = withContext(Dispatchers.IO) {
        try {
            val listRequest = Request.Builder()
                .url(server.modelsUrl())
                .get()
                .apply { server.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .build()
            client.newCall(listRequest).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ServerProbeResult(
                        reachable = true,
                        isCompatible = false,
                        error = "HTTP ${resp.code}",
                    )
                }
                try {
                    val root = JSON.parseToJsonElement(body).jsonObject
                    require(root["data"] is kotlinx.serialization.json.JsonArray) { "Missing model list" }
                    val models = JSON.decodeFromString(ModelList.serializer(), body).data
                    return@withContext ServerProbeResult(
                        reachable = true,
                        isCompatible = true,
                        models = models.map { it.id },
                    )
                } catch (e: Exception) {
                    // Port is open and returned 200, but not an OpenAI-compatible model list.
                    return@withContext ServerProbeResult(
                        reachable = true,
                        isCompatible = false,
                        error = "This endpoint did not return an OpenAI-compatible model list.",
                    )
                }
            }
        } catch (e: Exception) {
            ServerProbeResult(reachable = false, error = e.message ?: "Connection failed")
        }
    }

    /**
     * Stream a chat completion.
     *
     * @return an [EventSource] the caller must [EventSource.cancel] to stop early.
     * [onComplete] receives the full assembled text; it is also delivered via [onToken] deltas.
     */
    fun streamChat(
        server: ServerInfo,
        request: ChatRequest,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onContextTokens: (Int) -> Unit = {},
    ): EventSource {
        val json = request.toJsonString()
        val okRequest = Request.Builder()
            .url(server.chatCompletionsUrl())
            .post(json.toRequestBody("application/json".toMediaType()))
            .apply {
                server.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                header("Accept", "text/event-stream")
            }
            .build()

        val listener = object : okhttp3.sse.EventSourceListener() {
            private val buffer = StringBuilder()
            private var receivedEnd = false
            private var openedStream = false

            override fun onOpen(eventSource: EventSource, response: Response) {
                openedStream = true
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    receivedEnd = true
                    return
                }
                try {
                    val chunk = JSON.decodeFromString(ChatCompletionChunk.serializer(), data)
                    val choice = chunk.choices.firstOrNull()
                    if (choice?.finishReason != null) receivedEnd = true
                    val token = choice?.delta?.content
                    if (!token.isNullOrEmpty()) {
                        buffer.append(token)
                        onToken(token)
                    }
                    // Process text first: usage on the same frame includes it.
                    chunk.usage?.contextTokens?.takeIf { it >= 0 }?.let(onContextTokens)
                } catch (_: Exception) {
                    // Ignore non-JSON keep-alive lines.
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (receivedEnd) onComplete(buffer.toString())
                else onError(IOException("Connection closed before the response finished. Please retry."))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val full = buffer.toString()
                if (receivedEnd) {
                    onComplete(full)
                    response?.close()
                    return
                }
                if (full.isNotEmpty()) {
                    // The repository already has these tokens; retain them as an error,
                    // rather than silently treating a broken stream as a complete reply.
                    onError(IOException("Connection lost before the response finished. Please retry.", t))
                    response?.close()
                    return
                }
                if (openedStream) {
                    // SSE has already consumed the body, even if only a role or
                    // keep-alive frame arrived. It cannot be read again as JSON.
                    onError(IOException("Server closed the response before sending any text. Check the server logs and selected model, then retry.", t))
                    response?.close()
                    return
                }
                val resp = response
                if (resp != null) {
                    if (resp.isSuccessful) {
                        // The server answered a single non-streamed JSON body instead of SSE
                        // (OpenAI-compatible servers do this when `stream` is absent or
                        // ignored). Recover the full completion from the body.
                        try {
                            val body = resp.body?.string().orEmpty()
                            val root = JSON.parseToJsonElement(body)
                            root.jsonObject["usage"]?.jsonObject?.let { usage ->
                                JSON.decodeFromJsonElement(Usage.serializer(), usage).contextTokens
                            }
                                ?.takeIf { it >= 0 }?.let(onContextTokens)
                            val content = root.jsonObject["choices"]?.jsonArray
                                ?.firstOrNull()?.jsonObject
                                ?.get("message")?.jsonObject
                                ?.get("content")?.jsonPrimitive?.content
                            onComplete(content ?: "")
                        } catch (_: Exception) {
                            onError(RuntimeException(t?.message ?: "Unexpected server response"))
                        }
                        resp.close()
                        return
                    }
                    val msg = try {
                        val body = resp.body?.string().orEmpty()
                        "HTTP ${resp.code} ${body.take(300)}"
                    } catch (_: Exception) {
                        "HTTP ${resp.code}"
                    }
                    onError(RuntimeException(msg))
                    resp.close()
                    return
                }
                onError(RuntimeException(t?.message ?: "Connection failed"))
            }
        }

        return EventSources.createFactory(client).newEventSource(okRequest, listener)
    }

    companion object {
        fun buildClient(timeoutSeconds: Long = 30L): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // SSE streams stay open
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

        /** Separate client with a short timeout used by the network scanner. */
        fun buildScannerClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build()
    }
}
