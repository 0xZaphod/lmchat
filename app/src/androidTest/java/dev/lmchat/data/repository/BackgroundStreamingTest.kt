package dev.lmchat.data.repository

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmchat.LMChatApplication
import dev.lmchat.data.service.StreamingService
import dev.lmchat.domain.ServerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundStreamingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as LMChatApplication).container

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(10000) { while (!predicate()) delay(50) }
    }

    @Test fun serviceSurvivesOneChatStoppingAndStopActionSettlesAllRemainingReplies() = runBlocking {
        val server = MockWebServer().apply { start() }
        val repo = container.chatRepository
        val info = ServerInfo(server.url("/").toString(), server.port)
        val first = repo.createConversation(info, "background-test", null)
        val second = repo.createConversation(info, "background-test", null)
        val frame = "data: {\"choices\":[{\"delta\":{\"content\":\"Partial reply\"}}]}\n\n"
        repeat(2) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(frame + "\n".repeat(8000) + "data: [DONE]\n\n")
                .throttleBody(frame.toByteArray().size.toLong(), 1, TimeUnit.SECONDS))
        }
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            repo.sendChat(first, info, "background-test", "First synthetic chat", emptyList()) { _, _ -> }
            repo.sendChat(second, info, "background-test", "Second synthetic chat", emptyList()) { _, _ -> }
            withTimeout(10000) {
                repo.observeMessages(first.id).first { it.lastOrNull()?.content == "Partial reply" }
                repo.observeMessages(second.id).first { it.lastOrNull()?.content == "Partial reply" }
            }
            repo.stopStreaming(first.id)
            awaitCondition { first.id !in container.streamingKeepAlive.activeChats.value }
            assertTrue(container.streamingKeepAlive.serviceRunning)
            assertEquals(setOf(second.id), container.streamingKeepAlive.activeChats.value)
            assertEquals("streaming", repo.observeMessages(second.id).first().last().status)
            context.startService(android.content.Intent(context, StreamingService::class.java)
                .setAction("dev.lmchat.STOP_REPLIES"))
            awaitCondition { container.streamingKeepAlive.activeChats.value.isEmpty() && !container.streamingKeepAlive.serviceRunning }
            for (chat in listOf(first, second)) {
                val reply = repo.observeMessages(chat.id).first().last()
                assertEquals("Partial reply", reply.content)
                assertEquals("error", reply.status)
                assertEquals("Interrupted", reply.error)
            }
        } finally {
            repo.stopStreaming(first.id)
            repo.stopStreaming(second.id)
            awaitCondition { container.streamingKeepAlive.activeChats.value.isEmpty() }
            activity.close()
            repo.deleteConversation(first.id)
            repo.deleteConversation(second.id)
            server.shutdown()
        }
    }

    @Test fun backgroundRepliesKeepReceivingUntilCompletionAndReleaseService() = runBlocking {
        val server = MockWebServer().apply { start() }
        val repo = container.chatRepository
        val info = ServerInfo(server.url("/").toString(), server.port)
        val chat = repo.createConversation(info, "background-test", null)
        val power = context.getSystemService(PowerManager::class.java)
        // A secure keyguard cannot be dismissed by the test and would block later UI cases.
        val turnScreenOff = !context.getSystemService(KeyguardManager::class.java).isDeviceSecure ||
            InstrumentationRegistry.getArguments().getString("allowScreenLock") == "true"
        val frame = "data: {\"choices\":[{\"delta\":{\"content\":\"chunk \"}}]}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(frame.repeat(14) + "data: [DONE]\n\n")
            .throttleBody(frame.toByteArray().size.toLong(), 1, TimeUnit.SECONDS))
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            val assistant = repo.sendChat(chat, info, "background-test", "Synthetic background test", emptyList()) { _, _ -> }
            suspend fun content() = repo.observeMessages(chat.id).first().last().content
            withTimeout(10000) { repo.observeMessages(chat.id).first { it.lastOrNull()?.content?.isNotEmpty() == true } }
            awaitCondition { container.streamingKeepAlive.serviceRunning }
            assertTrue(container.streamingKeepAlive.activeChats.value.contains(chat.id))
            @Suppress("DEPRECATION")
            val services = context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
            assertTrue("Stream must have a foreground service", services.any {
                it.service.className == StreamingService::class.java.name && it.foreground
            })
            val beforeHome = content()
            shell("input keyevent KEYCODE_HOME")
            delay(2500)
            assertTrue("Reply must keep growing after Home", content().length > beforeHome.length)
            if (turnScreenOff) {
                val beforeLock = content()
                shell("input keyevent KEYCODE_SLEEP")
                awaitCondition { !power.isInteractive }
                delay(2500)
                assertTrue("Reply must keep growing with the screen off", content().length > beforeLock.length)
            }
            val final = withTimeout(25000) {
                repo.observeMessages(chat.id).first { it.lastOrNull()?.status == "done" }.last()
            }
            assertEquals(assistant, final.id)
            assertEquals("chunk ".repeat(14), final.content)
            assertNull(final.error)
            awaitCondition { !container.streamingKeepAlive.serviceRunning }
            assertTrue(container.streamingKeepAlive.activeChats.value.isEmpty())
        } finally {
            if (turnScreenOff) {
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
            }
            activity.close()
            repo.stopStreaming(chat.id)
            withTimeout(10000) {
                while (chat.id in container.streamingKeepAlive.activeChats.value) delay(50)
            }
            repo.deleteConversation(chat.id)
            server.shutdown()
        }
    }
}
