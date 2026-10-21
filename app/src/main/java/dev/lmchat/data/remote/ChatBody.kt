package dev.lmchat.data.remote

import dev.lmchat.domain.Attachment
import dev.lmchat.domain.AttachmentKind
import dev.lmchat.domain.ServerInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the OpenAI-compatible `content` payload for a message.
 *
 * Plain text  -> JSON string
 * With parts  -> JSON array of typed parts:
 *   { "type": "text", "text": "..." }
 *   { "type": "image_url", "image_url": { "url": "data:image/...;base64,..." } }
 */
object ChatBodyBuilder {

    /** Content for a simple text message. */
    fun textMessage(role: String, text: String): ChatMessageDto =
        ChatMessageDto(role = role, content = JsonPrimitive(text))

    /**
     * Content for a user message that may carry attachments.
     *
     * Document attachments are inlined as fenced text blocks so the model
     * sees their contents. Images are sent as `image_url` parts (requires a
     * vision-capable model on the server side).
     */
    fun userMessage(text: String, attachments: List<Attachment>): ChatMessageDto {
        val imageParts = attachments.filter { it.kind == AttachmentKind.IMAGE }
        val docParts = attachments.filter { it.kind == AttachmentKind.DOCUMENT }

        if (imageParts.isEmpty() && docParts.isEmpty()) {
            return textMessage("user", text)
        }

        val fullText = buildString {
            if (docParts.isNotEmpty()) {
                for (doc in docParts) {
                    appendLine("Attached document: ${doc.name ?: "document"}")
                    appendLine("```${doc.mimeType ?: "text/plain"}```")
                    appendLine(doc.text.orEmpty().take(MAX_DOC_CHARS))
                    appendLine("```")
                    appendLine()
                }
            }
            if (text.isNotBlank()) append(text)
        }

        val parts = buildList {
            if (fullText.isNotBlank()) add(buildJsonObject {
                put("type", "text")
                put("text", fullText)
            })
            for (img in imageParts) {
                val uri = img.dataUri() ?: continue
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", uri) })
                })
            }
        }
        return ChatMessageDto(role = "user", content = JsonArray(parts))
    }

    const val MAX_DOC_CHARS = 120_000
}

/** Convenience: serialize a [ChatRequest] to a JSON string. */
fun ChatRequest.toJsonString(): String = REQUEST_JSON.encodeToString(ChatRequest.serializer(), this)

/** Shared JSON instance used across the app (decoding + body building). */
val JSON: kotlinx.serialization.json.Json =
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

/**
 * JSON instance for encoding outgoing requests.
 *
 * Must keep `encodeDefaults = true`: [ChatRequest.stream] defaults to `true`,
 * so with defaults omitted the `stream` field would drop out of the request
 * body and LM Studio would answer a single non-streamed JSON document
 * instead of an SSE stream.
 */
val REQUEST_JSON: kotlinx.serialization.json.Json =
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

/** Build the base URL + chat completions endpoint for a server. */
fun ServerInfo.chatCompletionsUrl(): String = "${baseUrl}/v1/chat/completions"
fun ServerInfo.modelsUrl(): String = "${baseUrl}/v1/models"
