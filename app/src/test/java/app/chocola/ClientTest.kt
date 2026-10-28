package app.chocola

import app.chocola.api.ApiClient
import app.chocola.api.ApiFailure
import app.chocola.api.ModelIncomplete
import app.chocola.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ClientTest {
    @Test fun oversizedFirstAttachmentIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        val file = java.nio.file.Files.createTempFile("choco-context", ".txt").toFile()
        try {
            file.writeText("附件".repeat(40_000))
            MockWebServer().use { server ->
                val message = Message(sessionId = "s", role = "user", text = "分析", attachments = listOf(Attachment(file.name, file.path, "text/plain", file.length())))
                val result = runCatching { ApiClient().stream(provider(server).copy(contextWindow = 8192, maxTokens = 2048), "system", listOf(message)) {} }
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("附件"))
                assertEquals(0, server.requestCount)
            }
        } finally { file.delete() }
    }
    private fun provider(server: MockWebServer, protocol: Protocol = Protocol.CHAT) = Provider(baseUrl = server.url("/v1").toString(), protocol = protocol, model = "custom-model", apiKey = "test-only-key")
    private fun user() = listOf(Message(sessionId = "s", role = "user", text = "你好"))
    private val chat = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
    @Test fun realSseTransportPreservesUnicodeAndCustomHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(chat).throttleBody(9, 1, TimeUnit.MILLISECONDS))
            val values = mutableListOf<String>()
            val result = ApiClient().stream(provider(server).copy(headers = mapOf("X-Custom" to "custom-value")), "system", user()) { values += it }
            assertEquals("你好", result.text); assertEquals("你好", values.last())
            val req = server.takeRequest(); assertEquals("Bearer test-only-key", req.getHeader("Authorization")); assertEquals("custom-value", req.getHeader("X-Custom"))
            assertEquals("/v1/chat/completions", req.path); assertEquals("custom-model", JSONObject(req.body.readUtf8()).getString("model"))
        }
    }
    @Test fun anthropicUsesNativeAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"content":[{"type":"text","text":"OK"}]}"""))
            assertEquals("OK", ApiClient().stream(provider(server, Protocol.ANTHROPIC), "system", user()) {}.text)
            val req = server.takeRequest(); assertEquals("test-only-key", req.getHeader("x-api-key")); assertEquals("2023-06-01", req.getHeader("anthropic-version")); assertNull(req.getHeader("Authorization"))
        }
    }
    @Test fun authenticationFailureIsNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setChunkedBody("""{"error":{"message":"bad key"}}""", 6))
            try { ApiClient().stream(provider(server), "system", user()) {}; fail("Expected 401") }
            catch (e: ApiFailure) { assertEquals(401, e.code); assertTrue(e.message!!.contains("bad key")) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun brokenStreamAfterContentIsNotSilentlyReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
            val partial = mutableListOf<String>()
            try { ApiClient().stream(provider(server), "system", user()) { partial += it }; fail("Expected interrupted stream") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("提前结束")) }
            assertEquals("partial", partial.last()); assertEquals(1, server.requestCount)
        }
    }
    @Test fun stoppingClosesStreamingNetworkCallPromptly() = runBlocking {
        MockWebServer().use { server ->
            val unfinished = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n" + ": heartbeat\n\n".repeat(10000)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(unfinished).throttleBody(1024, 1, TimeUnit.SECONDS))
            val received = CompletableDeferred<Unit>()
            val task = launch(Dispatchers.IO) { ApiClient(client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()).stream(provider(server), "s", user()) { if (it == "partial") received.complete(Unit) } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(5000) { received.await() }
            withTimeout(3000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
        }
    }
    @Test fun stoppingBeforeResponseHeadersDoesNotBecomeANetworkFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = launch(Dispatchers.IO) { ApiClient().stream(provider(server), "s", user()) {} }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(3000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun aReasoningBudgetFailureWithoutVisibleTextIsNotBlindlyRetried() = runBlocking {
        MockWebServer().use { server ->
            val incomplete = """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],"usage":{"input_tokens":6000,"output_tokens":2048}}}"""
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $incomplete\n\n"))
            try { ApiClient().stream(provider(server, Protocol.RESPONSES), "summarize", user()) {}; fail("Expected a budget failure") }
            catch (e: ModelIncomplete) { assertEquals(2048, e.partial.outputTokens); assertEquals("max_output_tokens", e.reason) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun streamResponseCleanupNeverRunsOnTheUiCallerThread() = runBlocking {
        val closedOn = java.util.concurrent.atomic.AtomicReference<String>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-ui-thread") }
        executor.asCoroutineDispatcher().use { ui ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(chat + ": tail\n\n".repeat(100)))
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val original = requireNotNull(response.body)
                    val source = object : okio.ForwardingSource(original.source()) {
                        override fun close() { closedOn.set(Thread.currentThread().name); super.close() }
                    }.buffer()
                    response.newBuilder().body(object : okhttp3.ResponseBody() {
                        override fun contentType() = original.contentType()
                        override fun contentLength() = original.contentLength()
                        override fun source() = source
                    }).build()
                }.build()
                withContext(ui) { assertEquals("你好", ApiClient(client = client).stream(provider(server), "s", user()) {}.text) }
                assertNotNull(closedOn.get())
                assertNotEquals("fake-ui-thread", closedOn.get())
            }
        }
    }

}
