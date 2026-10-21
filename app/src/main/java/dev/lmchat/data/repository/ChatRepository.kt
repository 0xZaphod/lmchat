package dev.lmchat.data.repository

import androidx.room.withTransaction
import dev.lmchat.data.local.AppDatabase
import dev.lmchat.data.local.AttachmentEntity
import dev.lmchat.data.local.ConversationEntity
import dev.lmchat.data.local.MessageEntity
import dev.lmchat.data.local.MessageWithAttachments
import dev.lmchat.data.remote.ChatBodyBuilder
import dev.lmchat.data.remote.ChatMessageDto
import dev.lmchat.data.remote.ChatRequest
import dev.lmchat.data.remote.LmStudioClient
import dev.lmchat.data.remote.StreamOptions
import dev.lmchat.domain.Attachment
import dev.lmchat.domain.AttachmentKind
import dev.lmchat.domain.ServerInfo
import dev.lmchat.domain.ServerProbeResult
import dev.lmchat.domain.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.sse.EventSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns all conversation/message persistence and the chat streaming lifecycle.
 *
 * Streaming model:
 *  - the user message and an empty "streaming" assistant placeholder are
 *    persisted immediately,
 *  - tokens are accumulated and flushed to Room every [FLUSH_MS] (and on
 *    completion) so the UI can observe progress via [observeMessages],
 *  - [onProgress] additionally reports every token for buttery-smooth UI.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val client: LmStudioClient,
    private val scope: CoroutineScope,
) {

    private val conversations get() = db.conversationDao()
    private val messages get() = db.messageDao()
    private val attachments get() = db.attachmentDao()

    /** Flush interval for in-flight assistant text (ms). */
    private val FLUSH_MS = 200L

    @Volatile private var activeEventSource: EventSource? = null
    @Volatile private var activeStopRequested: AtomicBoolean? = null

    /* ---------------- Conversations ---------------- */

    fun observeConversations(): Flow<List<ConversationEntity>> = conversations.observeAll()

    fun observeConversation(id: String): Flow<ConversationEntity?> = conversations.observe(id)

    suspend fun createConversation(
        server: ServerInfo?,
        model: String?,
        systemPrompt: String?,
    ): ConversationEntity {
        val now = System.currentTimeMillis()
        val conv = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            createdAt = now,
            updatedAt = now,
            model = model?.takeIf { it.isNotBlank() },
            host = server?.host,
            serverType = server?.type?.name ?: "AUTO",
            contextLimitOverride = server?.contextLimitOverride,
            port = server?.port,
            systemPrompt = systemPrompt?.trim()?.takeIf { it.isNotBlank() },
        )
        conversations.insert(conv)
        return conv
    }

    suspend fun updateConversation(conv: ConversationEntity) = conversations.update(conv)

    suspend fun renameConversation(id: String, title: String) {
        conversations.updateTitle(id, title.trim().ifBlank { "Untitled" }, System.currentTimeMillis())
    }

    suspend fun deleteConversation(id: String) {
        messages.deleteByConversation(id)
        conversations.delete(id)
    }

    suspend fun deleteConversationIfEmpty(id: String): Boolean = db.withTransaction {
        if (messages.countByConversation(id) != 0) return@withTransaction false
        conversations.delete(id)
        true
    }

    /* ---------------- Messages ---------------- */

    fun observeMessages(conversationId: String): Flow<List<UiMessage>> =
        messages.observeByConversation(conversationId).map { list -> list.map { it.toUi() } }

    suspend fun deleteMessage(id: String) = messages.delete(id)

    /** Mark any "streaming" rows (left over from a crash/process death) as interrupted. */
    suspend fun cleanupStaleStreaming() {
        withContext(Dispatchers.IO) {
            messages.findActiveAll().forEach { msg ->
                messages.updateContent(
                    msg.id,
                    msg.content,
                    "error",
                    if (msg.content.isBlank()) "Interrupted" else "Interrupted mid-response",
                )
            }
        }
    }

    private fun MessageWithAttachments.toUi(): UiMessage =
        UiMessage(
            id = message.id,
            role = message.role,
            content = message.content,
            createdAt = message.createdAt,
            status = message.status,
            error = message.error,
            attachments = attachments.map { it.toDomain() },
        )

    private fun AttachmentEntity.toDomain(): Attachment =
        Attachment(
            kind = if (kind == "image") AttachmentKind.IMAGE else AttachmentKind.DOCUMENT,
            name = name,
            mimeType = mimeType,
            base64 = if (kind == "image") data.ifBlank { null } else null,
            text = if (kind == "document") data.ifBlank { null } else null,
        )

    /* ---------------- Chat streaming ---------------- */

    /**
     * Persist the user message (with attachments), insert an assistant placeholder
     * and start streaming the completion.
     *
     * @return the id of the assistant message being streamed.
     */
    suspend fun sendChat(
        conversation: ConversationEntity,
        server: ServerInfo,
        model: String,
        userText: String,
        userAttachments: List<Attachment>,
        persistUserMessage: Boolean = true,
        onProgress: (assistantId: String, fullText: String) -> Unit,
    ): String = withContext(Dispatchers.IO) {

        val now = System.currentTimeMillis()

        // 1. Persist the user message + attachments.
        if (persistUserMessage && (userText.isNotBlank() || userAttachments.isNotEmpty())) {
            val userMsgId = UUID.randomUUID().toString()
            messages.insert(
                MessageEntity(
                    id = userMsgId,
                    conversationId = conversation.id,
                    role = "user",
                    content = userText,
                    createdAt = now,
                    status = "done",
                    model = model,
                ),
            )
            if (userAttachments.isNotEmpty()) {
                attachments.insertAll(
                    userAttachments.map { a ->
                        AttachmentEntity(
                            id = UUID.randomUUID().toString(),
                            messageId = userMsgId,
                            kind = if (a.kind == AttachmentKind.IMAGE) "image" else "document",
                            name = a.name,
                            mimeType = a.mimeType,
                            data = if (a.kind == AttachmentKind.IMAGE) a.base64.orEmpty() else a.text.orEmpty(),
                        )
                    },
                )
            }
            // Auto-title the conversation from the first real user message.
            if (conversation.title.isBlank() || conversation.title == "New chat") {
                val firstLine = userText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                val title = if (firstLine.length > MAX_TITLE_CHARS) {
                    firstLine.take(MAX_TITLE_CHARS).trimEnd() + "…"
                } else firstLine
                if (title.isNotEmpty()) {
                    conversations.updateTitle(conversation.id, title, now)
                }
            }
        }

        // 2. Assistant placeholder.
        val assistantId = UUID.randomUUID().toString()
        messages.insert(
            MessageEntity(
                id = assistantId,
                conversationId = conversation.id,
                role = "assistant",
                content = "",
                createdAt = now + 1,
                status = "streaming",
                model = model,
            ),
        )

        // 3. Rebuild the full history for the request.
        val history = messages.observeByConversation(conversation.id)
            .first()
            .filter { it.message.id != assistantId }

        val requestMessages: List<ChatMessageDto> = buildList {
            conversation.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                add(ChatBodyBuilder.textMessage("system", it))
            }
            for (mwa in history) {
                if (mwa.message.content.isBlank() && mwa.attachments.isEmpty()) continue
                when (mwa.message.role) {
                    "assistant" -> add(ChatBodyBuilder.textMessage("assistant", mwa.message.content))
                    else -> add(
                        ChatBodyBuilder.userMessage(
                            mwa.message.content,
                            mwa.attachments.map { it.toDomain() },
                        ),
                    )
                }
            }
        }

        val request = ChatRequest(
            model = model,
            messages = requestMessages,
            stream = true,
            stream_options = StreamOptions(include_usage = true),
            temperature = 0.7,
            top_p = 0.95,
        )

        // 4. Stream.
        val buffer = StringBuilder()
        var lastFlushAt = 0L
        val finished = AtomicBoolean(false)
        val previous = conversations.getById(conversation.id)
        val initialPromptTokens = if (persistUserMessage && previous?.model == model && previous.contextTokens != null) {
            // The previous total already includes the whole preceding conversation.
            previous.contextTokens + estimateMessageTokens(ChatBodyBuilder.userMessage(userText, userAttachments))
        } else {
            requestMessages.sumOf(::estimateMessageTokens)
        }
        val tokenTracker = ContextTokenTracker(initialPromptTokens)
        suspend fun persistContextTokens() {
            val count = tokenTracker.snapshot()
            conversations.updateContextTokens(conversation.id, count.tokens, count.estimated)
        }
        persistContextTokens()
        val stopRequested = AtomicBoolean(false)
        activeStopRequested = stopRequested
        // Serializes the periodic flush writes and the final write so an
        // in-flight flush can never overwrite the completed text (IO pool is
        // multi-threaded and launch() does not preserve order).
        val writeMutex = Mutex()

        fun finalizeStreaming(status: String, error: String?) {
            if (!finished.compareAndSet(false, true)) return
            activeEventSource = null
            if (activeStopRequested === stopRequested) activeStopRequested = null
            val text = buffer.toString()
            scope.launch {
                writeMutex.lock()
                try {
                    persistContextTokens()
                    // Never replace the pre-request snapshot: it contains stale
                    // token counts, titles, and possibly server settings.
                    conversations.updateAfterResponse(conversation.id, model, System.currentTimeMillis())
                    if (text.isBlank() && error == null) {
                        // Nothing came back and there is no error: drop the empty placeholder.
                        messages.delete(assistantId)
                    } else {
                        // Kept even when blank so an empty-but-errored reply still
                        // shows its red error line instead of vanishing.
                        messages.updateContent(assistantId, text, status, error)
                    }
                } catch (_: Exception) {
                    // Best-effort persistence; the stream has already ended.
                } finally {
                    writeMutex.unlock()
                }
            }
        }

        activeEventSource = client.streamChat(
            server = server,
            request = request,
            onToken = { token ->
                buffer.append(token)
                val text = buffer.toString()
                tokenTracker.output(text)
                onProgress(assistantId, text)
                val now = System.currentTimeMillis()
                if (now - lastFlushAt >= FLUSH_MS) {
                    lastFlushAt = now
                    val toWrite = text
                    scope.launch {
                        writeMutex.lock()
                        try {
                            if (!finished.get()) {
                                db.withTransaction {
                                    persistContextTokens()
                                    messages.updateContent(assistantId, toWrite, "streaming", null)
                                }
                            }
                        } catch (_: Exception) {
                            // Non-fatal: we flush again and finalize later.
                        } finally {
                            writeMutex.unlock()
                        }
                    }
                }
            },
            onComplete = { _ ->
                if (stopRequested.get()) {
                    finalizeStreaming("error", "Interrupted")
                } else {
                    finalizeStreaming("done", null)
                }
            },
            onError = { e ->
                finalizeStreaming(
                    "error",
                    if (stopRequested.get()) "Interrupted" else e.message ?: "Request failed",
                )
            },
            onContextTokens = { tokens ->
                tokenTracker.reported(tokens)
                // Persist independently of the screen's ViewModel lifetime.
                // Read the latest value under the lock so queued writes cannot
                // restore an older usage count after a newer one.
                scope.launch {
                    writeMutex.lock()
                    try {
                        if (!finished.get()) {
                            persistContextTokens()
                        }
                    } finally {
                        writeMutex.unlock()
                    }
                }
            },
        )
        if (stopRequested.get()) activeEventSource?.cancel()

        assistantId
    }

    /** Stop the in-flight stream and preserve its partial response as retryable. */
    fun stopStreaming() {
        val stopRequested = activeStopRequested ?: return
        stopRequested.set(true)
        val source = activeEventSource
        activeEventSource = null
        source?.cancel()
    }

    /* ---------------- Server probing ---------------- */

    suspend fun probe(server: ServerInfo): ServerProbeResult = client.probe(server)

    companion object {
        const val MAX_TITLE_CHARS = 48
    }
}
