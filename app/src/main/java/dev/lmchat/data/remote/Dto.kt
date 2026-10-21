package dev.lmchat.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

/* ---------- OpenAI-compatible DTOs for the LM Studio server ---------- */

@Serializable
data class ModelList(
    val data: List<ModelDto> = emptyList(),
)

@Serializable
data class ModelDto(
    val id: String,
    @SerialName("object") val modelObject: String? = null,
    val owned_by: String? = null,
    val display_name: String? = null,
) {
    val displayId: String get() = display_name ?: id
}

@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    @SerialName("object") val chunkObject: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class StreamOptions(
    val include_usage: Boolean = true,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class Usage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null,
) {
    val contextTokens: Int?
        get() = total_tokens ?: prompt_tokens?.let { prompt -> completion_tokens?.let { prompt + it } }
}

/* ---------- Request builders ---------- */

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val max_tokens: Int? = null,
    val stream_options: StreamOptions? = null,
)

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: JsonElement,
)
