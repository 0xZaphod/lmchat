package dev.lmchat.data.util

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmchat.data.local.AppDatabase
import dev.lmchat.data.remote.JSON
import dev.lmchat.data.remote.LmStudioClient
import dev.lmchat.data.repository.ChatRepository
import dev.lmchat.domain.ServerInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DocumentAttachmentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private suspend fun attachment(name: String, bytes: ByteArray) = run {
        val file = File(context.cacheDir, "captures/$name")
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            AttachmentFactory.fromDocument(context, uri)
        } finally {
            file.delete()
        }
    }

    @Test fun pdfTextCanBeAttachedOnAndroid() = runBlocking {
        val bytes = instrumentation.context.assets.open("document.pdf").use { it.readBytes() }
        val result = attachment("regression.pdf", bytes)
        assertNotNull(result)
        assertEquals("application/pdf", result!!.mimeType)
        assertTrue(result.text!!.contains("LMChat document upload regression test."))
    }

    @Test fun invalidAndEmptyDocumentsAreRejectedWithoutCrashing() = runBlocking {
        assertNull(attachment("broken.pdf", "not a PDF".toByteArray()))
        assertNull(attachment("empty.txt", byteArrayOf()))
    }

    @Test fun textAndJsonDocumentsAreRead() = runBlocking {
        for ((name, text) in listOf("test.txt" to "Plain text document", "test.json" to "{\"message\":\"hello\"}")) {
            assertEquals(text, attachment(name, text.toByteArray())!!.text)
        }
    }

    @Test fun attachmentOnlyMessageIsPersistedAndSentAsText() = runBlocking {
        val pdf = instrumentation.context.assets.open("document.pdf").use { it.readBytes() }
        val document = attachment("send.pdf", pdf)!!
        val server = MockWebServer()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Received document\"}}]}\n\ndata: [DONE]\n\n"))
            server.start()
            val info = ServerInfo("127.0.0.1", server.port)
            val repo = ChatRepository(db, LmStudioClient(LmStudioClient.buildClient()), scope)
            val conv = repo.createConversation(info, "test-model", null)
            val id = repo.sendChat(conv, info, "test-model", "", listOf(document)) { _, _ -> }
            val messages = withTimeout(10000) {
                repo.observeMessages(conv.id).first { rows -> rows.any { it.id == id && it.status == "done" } }
            }
            assertEquals(document.text, messages.first { it.isUser }.attachments.single().text)
            assertEquals("Received document", messages.first { it.id == id }.content)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            val body = JSON.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertTrue(body["stream"]!!.jsonPrimitive.boolean)
            val user = body["messages"]!!.jsonArray.single().jsonObject
            val text = user["content"]!!.jsonArray.joinToString { it.jsonObject["text"]!!.jsonPrimitive.content }
            assertTrue(text.contains("Attached document: send.pdf"))
            assertTrue(text.contains("LMChat document upload regression test."))
        } finally {
            scope.cancel()
            db.close()
            server.shutdown()
        }
    }
}
