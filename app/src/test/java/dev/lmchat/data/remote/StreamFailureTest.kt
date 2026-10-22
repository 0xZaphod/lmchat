package dev.lmchat.data.remote

import dev.lmchat.domain.ServerInfo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamFailureTest {
    private val partial = "data: {\"choices\":[{\"delta\":{\"content\":\"Partial reply\"}}]}\n\n"

    private fun stream(response: MockResponse): Triple<String, String?, Throwable?> {
        MockWebServer().use { server ->
            server.enqueue(response)
            server.start()
            val settled = CountDownLatch(1)
            val tokens = StringBuilder()
            var complete: String? = null
            var failure: Throwable? = null
            val source = LmStudioClient(LmStudioClient.buildClient()).streamChat(
                ServerInfo(server.hostName, server.port), ChatRequest("model", emptyList()),
                onToken = { tokens.append(it) },
                onComplete = { complete = it; settled.countDown() },
                onError = { failure = it; settled.countDown() },
            )
            try {
                assertTrue("Stream did not settle", settled.await(5, TimeUnit.SECONDS))
                return Triple(tokens.toString(), complete, failure)
            } finally {
                source.cancel()
            }
        }
    }

    @Test fun prematureEofPreservesTokensAndReportsFailure() {
        val (tokens, complete, failure) = stream(MockResponse()
            .setHeader("Content-Type", "text/event-stream").setBody(partial))
        assertEquals("Partial reply", tokens)
        assertNull(complete)
        assertNotNull(failure)
    }

    @Test fun brokenTransferReportsFailureAfterDeliveringPartialText() {
        val body = partial + ": " + "keepalive".repeat(1024) + "\n\n"
        val (tokens, complete, failure) = stream(MockResponse()
            .setHeader("Content-Type", "text/event-stream").setBody(body)
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertEquals("Partial reply", tokens)
        assertNull(complete)
        assertNotNull(failure)
    }

    @Test fun terminalMarkersStillCompleteSuccessfully() {
        for (ending in listOf("data: [DONE]\n\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")) {
            val (tokens, complete, failure) = stream(MockResponse()
                .setHeader("Content-Type", "text/event-stream").setBody(partial + ending))
            assertEquals("Partial reply", tokens)
            assertEquals("Partial reply", complete)
            assertNull(failure)
        }
    }

    @Test fun connectionFailureWithoutTokensReportsError() {
        val (tokens, complete, failure) = stream(MockResponse().setResponseCode(503).setBody("Unavailable"))
        assertEquals("", tokens)
        assertNull(complete)
        assertNotNull(failure)
    }
}
