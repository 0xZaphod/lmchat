package dev.lmchat.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmchat.data.local.AppDatabase
import dev.lmchat.data.local.MessageEntity
import dev.lmchat.data.remote.LmStudioClient
import dev.lmchat.domain.ServerInfo
import dev.lmchat.domain.UiMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StreamingLifecycleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun withRepository(test: suspend (ChatRepository, AppDatabase, MockWebServer, ServerInfo) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = MockWebServer()
        server.start()
        try {
            test(ChatRepository(db, LmStudioClient(LmStudioClient.buildClient()), scope), db, server, ServerInfo("127.0.0.1", server.port))
        } finally {
            scope.cancel()
            db.close()
            server.shutdown()
        }
    }

    private fun heldReply(text: String): MockResponse {
        val event = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(event + "\n".repeat(8000) + "data: [DONE]\n\n")
            .throttleBody(event.toByteArray().size.toLong(), 1, TimeUnit.SECONDS)
    }

    private suspend fun awaitMessage(repo: ChatRepository, chat: String, predicate: (UiMessage) -> Boolean): UiMessage =
        withTimeout(10000) { repo.observeMessages(chat).first { it.any(predicate) }.first(predicate) }

    @Test fun concurrentChatsStopIndependentlyAndRejectDuplicateSends() = runBlocking {
        withRepository { repo, _, server, info ->
            val first = repo.createConversation(info, "model", null)
            val second = repo.createConversation(info, "model", null)
            server.enqueue(heldReply("First partial"))
            val firstId = repo.sendChat(first, info, "model", "one", emptyList()) { _, _ -> }
            awaitMessage(repo, first.id) { it.id == firstId && it.content == "First partial" }
            // Reject before persisting a second user message or starting another HTTP request.
            try {
                repo.sendChat(first, info, "model", "duplicate", emptyList()) { _, _ -> }
                fail("Duplicate send was accepted")
            } catch (_: IllegalStateException) {}
            assertEquals(2, repo.observeMessages(first.id).first().size)
            assertEquals(1, server.requestCount)
            server.enqueue(heldReply("Second partial"))
            val secondId = repo.sendChat(second, info, "model", "two", emptyList()) { _, _ -> }
            awaitMessage(repo, second.id) { it.id == secondId && it.content == "Second partial" }
            repo.stopStreaming(first.id)
            val stopped = awaitMessage(repo, first.id) { it.id == firstId && it.status == "error" }
            assertEquals("First partial", stopped.content)
            assertEquals("Interrupted", stopped.error)
            assertEquals("streaming", repo.observeMessages(second.id).first().last().status)
            repo.stopStreaming(second.id)
            awaitMessage(repo, second.id) { it.id == secondId && it.status == "error" }
        }
    }

    @Test fun callerCancellationDoesNotOrphanTheStreamAndRetryKeepsOneUserMessage() = runBlocking {
        withRepository { repo, _, server, info ->
            val chat = repo.createConversation(info, "model", null)
            server.enqueue(heldReply("Saved partial"))
            val caller = launch {
                repo.sendChat(chat, info, "model", "question", emptyList()) { _, _ -> }
                awaitCancellation()
            }
            val partial = awaitMessage(repo, chat.id) { it.isAssistant && it.content == "Saved partial" }
            caller.cancelAndJoin() // Models leaving the chat / destroying its ViewModel.
            // Startup cleanup must not touch a stream owned by this repository.
            repo.cleanupStaleStreaming()
            assertEquals("streaming", repo.observeMessages(chat.id).first().last().status)
            repo.stopStreaming(chat.id)
            awaitMessage(repo, chat.id) { it.id == partial.id && it.status == "error" }
            repo.deleteMessage(partial.id)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Complete\"}}]}\n\ndata: [DONE]\n\n"))
            val retryId = repo.sendChat(chat, info, "model", "question", emptyList(), persistUserMessage = false) { _, _ -> }
            awaitMessage(repo, chat.id) { it.id == retryId && it.status == "done" }
            val history = repo.observeMessages(chat.id).first()
            assertEquals(1, history.count { it.isUser })
            assertEquals("question", history.first().content)
            assertEquals("Complete", history.last().content)
        }
    }

    @Test fun historyMutationsInvalidateContextAndCascadeAttachments() = runBlocking {
        withRepository { repo, db, _, info ->
            val chat = repo.createConversation(info, "model", null)
            val dao = db.messageDao()
            dao.insert(MessageEntity("kept", chat.id, "user", "Prompt", 1))
            dao.insert(MessageEntity("deleted", chat.id, "assistant", "Reply", 2))
            db.attachmentDao().insert(dev.lmchat.data.local.AttachmentEntity(
                "attachment", "deleted", "document", "sample.txt", "text/plain", "Body"))
            db.conversationDao().updateContextTokens(chat.id, 2000)
            repo.deleteMessage("deleted")
            assertNull(db.conversationDao().getById(chat.id)?.contextTokens)
            val attachmentCount = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM attachments").use {
                it.moveToFirst(); it.getInt(0)
            }
            assertEquals(0, attachmentCount)
            db.conversationDao().updateContextTokens(chat.id, 1000)
            repo.updateMessageContent("kept", "Edited prompt")
            assertNull(db.conversationDao().getById(chat.id)?.contextTokens)
            dao.insert(MessageEntity("later", chat.id, "assistant", "Later reply", 3))
            db.conversationDao().updateContextTokens(chat.id, 3000)
            repo.deleteMessagesAfter(chat.id, "kept")
            assertNull(db.conversationDao().getById(chat.id)?.contextTokens)
            assertEquals(listOf("kept"), repo.observeMessages(chat.id).first().map { it.id })
            assertEquals("Edited prompt", repo.observeMessages(chat.id).first().single().content)
        }
    }

    @Test fun reopeningDatabaseRecoversPartialRepliesWithoutOverwritingExistingErrors() = runBlocking {
        val name = "stream-recovery.db"
        context.deleteDatabase(name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var db = AppDatabase.build(context, name)
            val chatId: String
            try {
                val repo = ChatRepository(db, LmStudioClient(LmStudioClient.buildClient()), scope)
                chatId = repo.createConversation(null, "model", null).id
                db.messageDao().insert(MessageEntity("partial", chatId, "assistant", "Saved before process exit", 1, "streaming"))
                db.messageDao().insert(MessageEntity("empty", chatId, "assistant", "", 2, "streaming"))
                db.messageDao().insert(MessageEntity("failed", chatId, "assistant", "", 3, "error", "HTTP 401"))
                db.messageDao().insert(MessageEntity("completed", chatId, "assistant", "Finished reply", 4, "done"))
                // A cleanup operation using an older snapshot must not undo finalization.
                db.messageDao().interruptStreaming("completed", "Interrupted")
            } finally { db.close() }
            db = AppDatabase.build(context, name)
            try {
                val repo = ChatRepository(db, LmStudioClient(LmStudioClient.buildClient()), scope)
                repo.cleanupStaleStreaming()
                val recovered = repo.observeMessages(chatId).first().associateBy { it.id }
                assertEquals("Saved before process exit", recovered.getValue("partial").content)
                assertEquals("Interrupted mid-response", recovered.getValue("partial").error)
                assertEquals("Interrupted", recovered.getValue("empty").error)
                assertEquals("HTTP 401", recovered.getValue("failed").error)
                assertEquals("done", recovered.getValue("completed").status)
                assertEquals("Finished reply", recovered.getValue("completed").content)
                assertNull(recovered.getValue("completed").error)
                assertTrue(recovered.values.filter { it.id != "completed" }.all { it.status == "error" })
            } finally { db.close() }
        } finally {
            scope.cancel()
            context.deleteDatabase(name)
        }
    }
}
