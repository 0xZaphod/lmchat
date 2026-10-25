package dev.lmchat.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmchat.LMChatApplication
import dev.lmchat.data.local.MessageEntity
import dev.lmchat.domain.ServerInfo
import dev.lmchat.ui.theme.LMChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StreamingScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun gesturesAndReadingPositionSurviveGrowingMarkdownReply() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LMChatApplication
        val container = app.container
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"data":[{"id":"scroll-test"}]}""")
            }
            start()
        }
        val conversation = container.chatRepository.createConversation(
            ServerInfo(server.url("/").toString(), server.port), "scroll-test", null,
        )
        val replyId = UUID.randomUUID().toString()
        val dao = container.database.messageDao()
        var reply = (1..80).joinToString("\n\n") { "**Paragraph $it**: a synthetic response with enough text to wrap across several lines on the phone." }
        try {
            repeat(20) { index ->
                dao.insert(MessageEntity(UUID.randomUUID().toString(), conversation.id, "user",
                    "Earlier message $index\n".repeat(4), index.toLong()))
            }
            dao.insert(MessageEntity(replyId, conversation.id, "assistant", reply, 100, "streaming"))
            compose.setContent { LMChatTheme { ChatScreen(conversation.id, {}, {}) } }
            val list = compose.onNode(hasScrollToIndexAction())
            compose.waitUntil(10000) {
                runCatching { list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f }.getOrDefault(false)
            }
            fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.waitForIdle()
            delay(300)
            compose.waitForIdle()
            repeat(5) { index ->
                reply += "\n\nUnsolicited viewport movement check $index."
                dao.updateContent(replyId, reply, "streaming", null)
                delay(100)
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Jump to latest message").assertDoesNotExist()
            assertTrue("Streaming at the bottom must follow new text",
                list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() == it.maxValue() })
            // Enter the oversized native Markdown view, then drag back while text
            // keeps arriving through the same Room -> ViewModel -> UI path as SSE.
            compose.waitForIdle()
            val beforeGesture = position()
            val updates = launch(Dispatchers.IO) {
                repeat(30) { index ->
                    reply += "\n\nStreaming paragraph $index with **bold** and `code`."
                    dao.updateContent(replyId, reply, "streaming", null)
                    delay(60)
                }
            }
            list.performTouchInput { swipeDown(durationMillis = 600) }
            compose.waitForIdle()
            assertTrue("Dragging toward earlier text must move the viewport", position() < beforeGesture)
            updates.join()
            compose.waitForIdle()
            val readingPosition = position()
            repeat(8) { index ->
                reply += "\n\nMore streamed text $index."
                dao.updateContent(replyId, reply, "streaming", null)
                delay(100)
            }
            compose.waitForIdle()
            assertEquals("Appended text must not move the reading position", readingPosition, position(), 0.0001f)
            list.performScrollToIndex(3)
            compose.waitForIdle()
            val historyPosition = position()
            dao.updateContent(replyId, "$reply\n\nFinal text", "done", null)
            delay(300)
            compose.waitForIdle()
            assertEquals("Finishing a reply must not pull history back down", historyPosition, position(), 0.0001f)
            compose.onNodeWithContentDescription("Jump to latest message").assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Jump to latest message").assertDoesNotExist()
            reply += "\n\nFollowing must resume after tapping the arrow.\n".repeat(20)
            dao.updateContent(replyId, reply, "streaming", null)
            delay(350)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Jump to latest message").assertDoesNotExist()
            assertTrue("Arrow must resume following at the bottom", !list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() < it.maxValue() })
        } finally {
            container.chatRepository.deleteConversation(conversation.id)
            server.shutdown()
        }
    }
}
