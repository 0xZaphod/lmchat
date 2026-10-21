package dev.lmchat.data.remote

import dev.lmchat.domain.Attachment
import dev.lmchat.domain.AttachmentKind
import dev.lmchat.domain.serverPort
import dev.lmchat.domain.ServerInfo
import dev.lmchat.domain.ServerType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerCompatibilityTest {
    @Test fun fullUrlsDoNotRequireASeparatePort() {
        assertEquals(443, serverPort("https://models.example.com", ""))
        assertEquals(8443, serverPort("https://models.example.com:8443", ""))
        assertEquals(80, serverPort("http://models.example.com", "garbage"))
        assertEquals(1234, serverPort("100.100.10.20", "1234"))
        assertNull(serverPort("100.100.10.20", ""))
        assertNull(serverPort("https://", "1234"))
    }

    @Test fun serverUrlsPreserveHttpsAndProxyPorts() {
        assertEquals("https://models.example.com",
            ServerInfo("https://models.example.com/", 1234).baseUrl)
        assertEquals("https://models.example.com:8443/proxy",
            ServerInfo("https://models.example.com:8443/proxy/", 1234).baseUrl)
        assertEquals("http://192.168.1.20:1234", ServerInfo("192.168.1.20", 1234).baseUrl)
        assertEquals("http://localhost:8080", ServerInfo("http://localhost:8080", 1234).baseUrl)
    }

    private fun json(text: String) = JSON.parseToJsonElement(text).jsonObject

    @Test fun contextUsageIncludesBothPromptAndCompletionTokens() {
        assertEquals(18, Usage(prompt_tokens = 16, completion_tokens = 2).contextTokens)
        assertEquals(21, Usage(prompt_tokens = 16, completion_tokens = 2, total_tokens = 21).contextTokens)
        // A prompt-only value must not be presented as the final context total.
        assertNull(Usage(prompt_tokens = 16).contextTokens)
    }

    @Test fun lmStudioUsesLoadedInstanceAndVisionFlagRatherThanTrainingMaximum() {
        val metadata = MetadataParser.lmStudio(json("""{"models":[
            {"key":"vendor/model@q3","max_context_length":262144,"loaded_instances":[],"capabilities":{"vision":true}},
            {"key":"vendor/model@q4","max_context_length":262144,"loaded_instances":[
                {"id":"model","config":{"context_length":80128}}],"capabilities":{"vision":true}}
        ]}"""), "model")!!
        assertEquals(80128, metadata.contextLength)
        assertEquals(true, metadata.supportsImages)
    }

    @Test fun unloadedTextModelHasNoConfiguredLimitAndNoImages() {
        val metadata = MetadataParser.lmStudio(json("""{"models":[{
            "key":"text-model","max_context_length":131072,"loaded_instances":[],"capabilities":{"vision":false}
        }]}"""), "text-model")!!
        assertNull(metadata.contextLength)
        assertEquals(false, metadata.supportsImages)
    }

    @Test fun unloadedVariantsMustAgreeBeforeVisionIsConfirmed() {
        val allVision = json("""{"models":[
            {"key":"model@q3","capabilities":{"vision":true}},
            {"key":"model@q4","capabilities":{"vision":true}}
        ]}""")
        assertEquals(true, MetadataParser.lmStudio(allVision, "model")!!.supportsImages)
        assertNull(MetadataParser.lmStudio(allVision, "model")!!.contextLength)
        val mixed = json("""{"models":[
            {"key":"model@q3","capabilities":{"vision":true}},
            {"key":"model@q4","capabilities":{"vision":false}}
        ]}""")
        assertNull(MetadataParser.lmStudio(mixed, "model")!!.supportsImages)
    }

    @Test fun ollamaUsesRunningContextAndExplicitCapabilities() {
        val show = json("""{"capabilities":["completion","vision"],"model_info":{"llama.context_length":131072}}""")
        val running = json("""{"models":[{"name":"model:latest","context_length":8192}]}""")
        assertEquals(8192, MetadataParser.ollama(show, running, "model")!!.contextLength)
        assertEquals(true, MetadataParser.ollama(show, running, "model")!!.supportsImages)
        assertNull(MetadataParser.ollama(show, null, "model")!!.contextLength)
        assertEquals(false, MetadataParser.ollama(json("""{"capabilities":["completion"]}"""), null, "model")!!.supportsImages)
    }

    @Test fun llamaCppUsesSlotContextWithoutDividingBySlotCount() {
        val metadata = MetadataParser.llamaCpp(json("""{
            "default_generation_settings":{"n_ctx":16384},"total_slots":4,"modalities":{"vision":true}
        }"""))!!
        assertEquals(16384, metadata.contextLength)
        assertEquals(true, metadata.supportsImages)
    }

    @Test fun genericMetadataDoesNotGuessVisionFromModelName() {
        val root = json("""{"data":[
            {"id":"vision-sounding-name","max_context_length":131072},
            {"id":"actual-vision","architecture":{"input_modalities":["text","image"]},"max_model_len":4096},
            {"id":"text","input_modalities":["text"]}
        ]}""")
        assertNull(MetadataParser.generic(root, "vision-sounding-name")!!.supportsImages)
        assertNull(MetadataParser.generic(root, "vision-sounding-name")!!.contextLength)
        assertEquals(true, MetadataParser.generic(root, "actual-vision")!!.supportsImages)
        assertEquals(4096, MetadataParser.generic(root, "actual-vision")!!.contextLength)
        assertEquals(false, MetadataParser.generic(root, "text")!!.supportsImages)
        assertNull(MetadataParser.generic(root, "missing"))
    }

    @Test fun ollamaMetadataRequestsIncludeAuthenticationAndModel() = runBlocking {
        MockWebServer().use { mock ->
            mock.enqueue(MockResponse().setBody("""{"capabilities":["completion","vision"]}"""))
            mock.enqueue(MockResponse().setBody("""{"models":[{"name":"vision:latest","context_length":4096}]}"""))
            mock.start()
            val info = ServerInfo(mock.hostName, mock.port, "test-key", ServerType.OLLAMA)
            val result = ModelMetadataReader(LmStudioClient.buildClient()).read(info, "vision")
            assertEquals(true, result.supportsImages)
            assertEquals(4096, result.contextLength)
            val show = mock.takeRequest()
            assertEquals("/api/show", show.path)
            assertEquals("POST", show.method)
            assertEquals("Bearer test-key", show.getHeader("Authorization"))
            assertEquals("\"vision\"", json(show.body.readUtf8())["model"].toString())
            assertEquals("/api/ps", mock.takeRequest().path)
        }
    }

    @Test fun autodetectFallsThroughToLlamaCppAndEncodesModelQuery() = runBlocking {
        MockWebServer().use { mock ->
            mock.enqueue(MockResponse().setResponseCode(404))
            mock.enqueue(MockResponse().setResponseCode(404))
            mock.enqueue(MockResponse().setBody("""{"modalities":{"vision":false},"default_generation_settings":{"n_ctx":8192}}"""))
            mock.start()
            val result = ModelMetadataReader(LmStudioClient.buildClient())
                .read(ServerInfo(mock.hostName, mock.port), "vendor/model:Q4")
            assertEquals(false, result.supportsImages)
            assertEquals(8192, result.contextLength)
            mock.takeRequest()
            mock.takeRequest()
            assertEquals("vendor/model:Q4", mock.takeRequest().requestUrl!!.queryParameter("model"))
        }
    }

    @Test fun missingMetadataPreservesUnknownVisionAndAllowsManualContextLimit() = runBlocking {
        MockWebServer().use { mock ->
            mock.enqueue(MockResponse().setResponseCode(404))
            mock.start()
            val result = ModelMetadataReader(LmStudioClient.buildClient()).read(
                ServerInfo(mock.hostName, mock.port, type = ServerType.OPENAI_COMPATIBLE, contextLimitOverride = 32768), "model")
            assertNull(result.supportsImages)
            assertEquals(32768, result.contextLength)
        }
    }

    @Test fun probeRejectsSuccessfulHtmlButAcceptsEmptyCompatibleModelList() = runBlocking {
        MockWebServer().use { mock ->
            mock.enqueue(MockResponse().setBody("<html>LM Studio data</html>"))
            mock.enqueue(MockResponse().setBody("""{"data":[]}"""))
            mock.start()
            val client = LmStudioClient(LmStudioClient.buildClient())
            val info = ServerInfo(mock.hostName, mock.port)
            assertFalse(client.probe(info).isCompatible)
            assertTrue(client.probe(info).isCompatible)
        }
    }

    @Test fun allProvidersShareStreamingChatWithImagePartsAndUsage() {
        ServerType.entries.forEach { type ->
            MockWebServer().use { mock ->
                mock.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":8,\"total_tokens\":10}}\n\n" +
                    "data: [DONE]\n\n"))
                mock.start()
                val client = LmStudioClient(LmStudioClient.buildClient())
                val completion = CountDownLatch(1)
                var response = ""
                var error: Throwable? = null
                var usage: Int? = null
                client.streamChat(
                    ServerInfo(mock.hostName, mock.port, "test-key", type),
                    ChatRequest("vision", listOf(ChatBodyBuilder.userMessage("Describe", listOf(
                        Attachment(AttachmentKind.IMAGE, "photo.jpg", "image/jpeg", "aW1hZ2U=")
                    ))), stream_options = StreamOptions()),
                    onToken = {}, onComplete = { response = it; completion.countDown() },
                    onError = { error = it; completion.countDown() }, onContextTokens = { usage = it },
                )
                assertTrue(completion.await(5, TimeUnit.SECONDS))
                assertNull(error)
                assertEquals("Hello", response)
                assertEquals(10, usage)
                val request = mock.takeRequest()
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("Bearer test-key", request.getHeader("Authorization"))
                val body = request.body.readUtf8()
                assertTrue(body.contains("\"stream\":true"))
                assertTrue(body.contains("data:image/jpeg;base64,aW1hZ2U="))
                assertTrue(body.contains("Describe"))
            }
        }
    }
}
