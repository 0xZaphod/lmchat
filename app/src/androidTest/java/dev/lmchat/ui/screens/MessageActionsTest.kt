package dev.lmchat.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmchat.LMChatApplication
import dev.lmchat.data.local.AttachmentEntity
import dev.lmchat.data.local.MessageEntity
import dev.lmchat.domain.ServerInfo
import dev.lmchat.ui.theme.LMChatTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class MessageActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LMChatApplication).container

    private fun withChat(test: (String, CopyOnWriteArrayList<String>) -> Unit) {
        val requests = CopyOnWriteArrayList<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/chat/completions") {
                        requests += request.body.readUtf8()
                        return MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Replacement reply\"}}]}\n\ndata: [DONE]\n\n")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"data":[{"id":"actions-test"}]}""")
                }
            }
            start()
        }
        val chat = runBlocking { container.chatRepository.createConversation(ServerInfo(server.url("/").toString(), server.port), "actions-test", null) }
        try { test(chat.id, requests) } finally {
            runBlocking { container.chatRepository.deleteConversation(chat.id) }
            server.shutdown()
        }
    }

    private fun insert(chat: String, role: String, content: String, time: Long): String = runBlocking {
        UUID.randomUUID().toString().also {
            container.database.messageDao().insert(MessageEntity(it, chat, role, content, time))
        }
    }

    private fun showChat(chat: String): ChatViewModel {
        compose.setContent { LMChatTheme { ChatScreen(chat, {}, {}) } }
        lateinit var vm: ChatViewModel
        compose.runOnIdle {
            vm = ViewModelProvider(compose.activity, ChatViewModel.factory(container, chat))["chat_$chat", ChatViewModel::class.java]
        }
        compose.waitUntil(10000) { vm.messages.value.isNotEmpty() && vm.server.value != null && vm.models.value.isNotEmpty() }
        return vm
    }

    @Test fun attachmentOnlyRegenerationRejectsOverlappingActionsAndKeepsDocument() = withChat { chat, requests ->
        val user = insert(chat, "user", "", 1)
        runBlocking {
            container.database.attachmentDao().insert(AttachmentEntity(UUID.randomUUID().toString(), user,
                "document", "sample.txt", "text/plain", "Document body"))
        }
        insert(chat, "assistant", "Old reply", 2)
        val vm = showChat(chat)
        compose.runOnIdle {
            vm.resend(vm.messages.value.first())
            vm.resend(vm.messages.value.first())
            vm.deleteMessage(user)
            assertFalse(vm.send("overlapping send"))
        }
        compose.waitUntil(10000) { vm.messages.value.lastOrNull()?.content == "Replacement reply" && !vm.isBusy.value }
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("Document body"))
        val history = runBlocking { container.chatRepository.observeMessages(chat).first() }
        assertEquals(2, history.size)
        assertEquals(user, history.first().id)
        assertEquals("Document body", history.first().attachments.single().text)
    }

    @Test fun regeneratingTextAfterAnImageRequiresImagePermissionBeforeTruncating() = withChat { chat, requests ->
        val imageUser = insert(chat, "user", "Image prompt", 1)
        runBlocking {
            container.database.attachmentDao().insert(AttachmentEntity(UUID.randomUUID().toString(), imageUser,
                "image", "image.png", "image/png", "AA=="))
        }
        insert(chat, "assistant", "Image reply", 2)
        val textUser = insert(chat, "user", "Text prompt", 3)
        val last = insert(chat, "assistant", "Keep this reply", 4)
        val vm = showChat(chat)
        compose.runOnIdle { vm.editAndResend(textUser, "Changed prompt") }
        compose.runOnIdle {
            assertTrue(vm.notice.value.orEmpty().contains("Image support has not been confirmed"))
        }
        val history = runBlocking { container.chatRepository.observeMessages(chat).first() }
        assertEquals(last, history.last().id)
        assertEquals("Text prompt", history.first { it.id == textUser }.content)
        assertTrue(requests.isEmpty())
    }

    @Test fun messageMenuEditsRegeneratesAndDeletes() = withChat { chat, requests ->
        val user = insert(chat, "user", "Original prompt", 1)
        insert(chat, "assistant", "Old reply", 2)
        val vm = showChat(chat)
        compose.onAllNodesWithContentDescription("Message actions")[0].performClick()
        compose.onNodeWithText("Edit and resend").performClick()
        compose.onNode(hasSetTextAction() and androidx.compose.ui.test.hasText("Original prompt")).performTextReplacement("Edited prompt")
        compose.onNodeWithText("Resend").performClick()
        compose.waitUntil(10000) { vm.messages.value.lastOrNull()?.content == "Replacement reply" && !vm.isBusy.value }
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("Edited prompt"))
        assertFalse(requests.single().contains("Old reply"))
        compose.onAllNodesWithContentDescription("Message actions")[1].performClick()
        compose.onNodeWithText("Delete message").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10000) { vm.messages.value.size == 1 && !vm.isBusy.value }
        assertEquals(user, vm.messages.value.single().id)
        assertNull(runBlocking { container.database.conversationDao().getById(chat) }?.contextTokens)
    }
}
