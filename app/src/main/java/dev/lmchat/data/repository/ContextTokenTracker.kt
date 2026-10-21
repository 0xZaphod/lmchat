package dev.lmchat.data.repository

import dev.lmchat.data.remote.ChatMessageDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class ContextTokenCount(val tokens: Int, val estimated: Boolean)

/** Server usage is cumulative for the request, which already includes chat history. */
internal class ContextTokenTracker(initialPromptTokens: Int) {
    private var baselineTokens = initialPromptTokens
    private var baselineCharacters = 0
    private var outputCharacters = 0
    private var count = ContextTokenCount(initialPromptTokens, estimated = true)

    @Synchronized
    fun output(fullText: String) {
        outputCharacters = fullText.length
        val newCharacters = (outputCharacters - baselineCharacters).coerceAtLeast(0)
        if (newCharacters > 0) {
            count = ContextTokenCount(baselineTokens + (newCharacters + 3) / 4, estimated = true)
        }
    }

    @Synchronized
    fun reported(tokens: Int) {
        baselineTokens = tokens
        baselineCharacters = outputCharacters
        count = ContextTokenCount(tokens, estimated = false)
    }

    @Synchronized
    fun snapshot(): ContextTokenCount = count
}

/** Text-only fallback; image/tokenizer overhead is unknown until the server reports usage. */
internal fun estimateMessageTokens(message: ChatMessageDto): Int {
    val characters = when (val content = message.content) {
        is JsonPrimitive -> content.contentOrNull?.length ?: 0
        is JsonArray -> content.sumOf { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.length ?: 0
        }
        else -> 0
    }
    return (characters + 3) / 4 + 4
}
