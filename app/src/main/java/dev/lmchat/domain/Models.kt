package dev.lmchat.domain

import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ServerType(val label: String, val defaultPort: Int) {
    AUTO("Auto-detect", 1234),
    LM_STUDIO("LM Studio", 1234),
    OLLAMA("Ollama", 11434),
    LLAMA_CPP("llama.cpp", 8080),
    OPENAI_COMPATIBLE("OpenAI-compatible (vLLM, LocalAI, others)", 8000);

    companion object {
        fun fromStorage(value: String?): ServerType = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

data class ModelMetadata(
    val supportsImages: Boolean? = null,
    val contextLength: Int? = null,
)

/** Full URLs supply their own port; bare hosts require a separate valid port. */
fun serverPort(host: String, portText: String): Int? {
    val address = host.trim()
    if (address.startsWith("https://", true) || address.startsWith("http://", true)) {
        return address.toHttpUrlOrNull()?.port
    }
    return portText.toIntOrNull()?.takeIf { it in 1..65535 }
}

/** A server endpoint the user has configured or discovered. */
data class ServerInfo(
    val host: String,
    val port: Int,
    val apiKey: String? = null,
    val type: ServerType = ServerType.AUTO,
    val contextLimitOverride: Int? = null,
) {
    // A full URL owns its scheme and port; the separate port is for bare hosts.
    val baseUrl: String get() {
        val address = host.trim().trimEnd('/')
        return if (address.startsWith("https://", ignoreCase = true) ||
            address.startsWith("http://", ignoreCase = true)) address
        else "http://$address:$port"
    }
    val label: String get() = baseUrl.removePrefix("http://")
}

/** Result of probing a host on the local network. */
data class DiscoveredServer(
    val host: String,
    val port: Int,
    val isCompatible: Boolean,
    val models: List<String> = emptyList(),
) {
    val serverInfo: ServerInfo get() = ServerInfo(host, port, null)
}

/** Attachment kinds a chat message can carry. */
enum class AttachmentKind { IMAGE, DOCUMENT }

/**
 * An attachment attached to a user message.
 *
 * @param base64 raw base64 of the original file bytes (images: the decoded/resized image)
 * @param text for documents: extracted plain text that gets sent to the model
 */
data class Attachment(
    val kind: AttachmentKind,
    val name: String?,
    val mimeType: String?,
    val base64: String? = null,
    val text: String? = null,
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }

    /** Data URI usable in an OpenAI-style `image_url` part. */
    fun dataUri(): String? =
        if (kind == AttachmentKind.IMAGE && base64 != null) "data:${mimeType ?: "image/jpeg"};base64,$base64" else null
}

/** A message as rendered in the UI (room rows + attachments merged). */
data class UiMessage(
    val id: String,
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val createdAt: Long,
    val status: String, // "done" | "streaming" | "error"
    val error: String?,
    val attachments: List<Attachment> = emptyList(),
) {
    val isUser: Boolean get() = role == "user"
    val isAssistant: Boolean get() = role == "assistant"
}

/** Result of probing an OpenAI-compatible model server. */
data class ServerProbeResult(
    val reachable: Boolean,
    val isCompatible: Boolean = false,
    val models: List<String> = emptyList(),
    val error: String? = null,
)

/** Server connection state for the chat top-bar indicator. */
enum class ConnectionStatus {
    CONNECTED,
    CHECKING,
    ERROR,
}
