package app.chocola

import app.chocola.api.*
import app.chocola.data.*
import app.chocola.data.Protocol
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class ImageClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6S7sAAAAASUVORK5CYII=")
    private val encoded get() = Base64.getEncoder().encodeToString(png)
    private fun provider(server: MockWebServer) = Provider(baseUrl = server.url("/v1/responses").toString(), protocol = Protocol.RESPONSES,
        model = "gpt-image-2.5-sunburst", apiKey = "image-test-key", headers = mapOf("X-Proxy-Key" to "test-proxy-value"))
    private fun response() = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":[{"b64_json":"$encoded"}]}""")
    private suspend fun generate(server: MockWebServer, config: ImageSettings = ImageSettings(), refs: List<Attachment> = emptyList(), client: ImageClient = ImageClient()) =
        client.generate(provider(server), config, "画一只鹈鹕 🐦", refs, temporary.newFolder(), temporary.newFolder(), "test", "gpt-6.1-sol")

    @Test fun directImageModelUsesGenerationEndpointAndItsOwnParameters() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response())
            val result = generate(server, ImageSettings(quality = "max", size = "1536x864", background = "transparent"))
            assertArrayEquals(png, File(result.images.single().path).readBytes())
            val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
            assertEquals("/v1/images/generations", request.path); assertEquals("Bearer image-test-key", request.getHeader("Authorization"))
            assertEquals("test-proxy-value", request.getHeader("X-Proxy-Key")); assertEquals("gpt-image-2.5-sunburst", body.getString("model"))
            assertEquals("max", body.getString("quality")); assertFalse(body.has("max_output_tokens")); assertEquals(1, server.requestCount)
        }
    }
    @Test fun referencesUseMultipartEditsWithARealBoundaryAndPreserveBinaryBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response())
            val file = temporary.newFile("reference.png").apply { writeBytes(png) }
            val reference = Attachment(file.name, file.path, "image/png", file.length())
            val custom = provider(server).copy(headers = mapOf("Content-Type" to "application/json", "X-Custom" to "yes"))
            ImageClient().generate(custom, ImageSettings(), "修改背景", listOf(reference), temporary.newFolder(), temporary.newFolder(), "test")
            val request = server.takeRequest(); val body = request.body.readByteArray()
            assertEquals("/v1/images/edits", request.path); assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            assertTrue(String(body, Charsets.ISO_8859_1).contains("name=\"image\"; filename=\"reference.png\""))
            assertTrue(body.asList().windowed(png.size).any { it == png.asList() }); assertEquals("yes", request.getHeader("X-Custom"))
        }
    }
    @Test fun responsesModeStreamsReferenceDataWithoutPuttingTheImageModelAtTopLevel() = runBlocking {
        MockWebServer().use { server ->
            val final = """{"type":"response.completed","response":{"output":[{"type":"image_generation_call","result":"$encoded"}]}}"""
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $final\n\n"))
            val file = temporary.newFile("edit.png").apply { writeBytes(png) }
            val result = generate(server, ImageSettings(transport = "responses", routingModel = "gpt-6.1-sol"), listOf(Attachment(file.name, file.path, "image/png", file.length())))
            assertEquals(1, result.images.size)
            val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
            assertEquals("/v1/responses", request.path); assertEquals("gpt-6.1-sol", body.getString("model"))
            assertEquals("gpt-image-2.5-sunburst", body.getJSONArray("tools").getJSONObject(0).getString("model"))
            val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
            assertEquals("画一只鹈鹕 🐦", content.getJSONObject(0).getString("text"))
            assertEquals("data:image/png;base64,$encoded", content.getJSONObject(1).getString("image_url"))
        }
    }
    @Test fun imageEventsWithoutAContentTypeAreDecodedWithoutLosingTheFirstEvent() = runBlocking {
        MockWebServer().use { server ->
            val final = """{"type":"response.completed","response":{"output":[{"type":"image_generation_call","result":"$encoded"}]}}"""
            server.enqueue(MockResponse().setBody("data: $final\n\n"))
            val result = generate(server, ImageSettings(transport = "responses", routingModel = "gpt-6.1-sol"))
            assertArrayEquals(png, File(result.images.single().path).readBytes())
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun nativeCodexImagesUsePrivateAuthorizationAndPublishEachCompletedSlot() = runBlocking {
        MockWebServer().use { server ->
            val second = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
            listOf(encoded, second).forEach { server.enqueue(MockResponse().setBody("data: {\"type\":\"image_generation.completed\",\"b64_json\":\"$it\"}\n\n")) }
            var authorized = 0; var observed = 0
            val access = object : PrivateModelAccess {
                override suspend fun request(provider: Provider, session: String, body: JSONObject): Request = error("Image requests must use the image route")
                override suspend fun imageRequest(provider: Provider, session: String, request: Request): Request {
                    authorized++; return request.newBuilder().header("Authorization", "Bearer private-image-account").build()
                }
                override suspend fun response(request: Request, response: Response) { observed++ }
                override suspend fun models() = emptyList<String>()
            }
            val provider = CodexModel("gpt-image-2.5-sunburst").provider().copy(baseUrl = server.url("/v1").toString())
            val progress = mutableListOf<Int>()
            val result = ImageClient(privateModels = access).generate(provider, ImageSettings(count = 2, quality = "max", size = "3840x2160"), "Two variations", emptyList(),
                temporary.newFolder(), temporary.newFolder(), "s", onImages = { progress += it.size })
            assertEquals(listOf(1, 2), progress); assertEquals(2, result.images.size)
            assertEquals(2, authorized); assertEquals(2, observed); assertEquals(2, server.requestCount)
            repeat(2) {
                val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
                assertEquals("/v1/images/generations", request.path); assertEquals("Bearer private-image-account", request.getHeader("Authorization"))
                assertTrue(body.getBoolean("stream")); assertEquals(1, body.getInt("n")); assertEquals("max", body.getString("quality"))
                assertEquals("3840x2160", body.getString("size")); assertFalse(body.has("reasoning")); assertTrue(provider.apiKey.isBlank())
            }
        }
    }
    @Test fun nativeCodexEditsStreamSingleAndMultipleReferencesAsOrderedJsonInsteadOfMultipart() = runBlocking {
        val second = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val a = temporary.newFile("reference-a.png").apply { writeBytes(png) }
        val b = temporary.newFile("reference-b.png").apply { writeBytes(Base64.getDecoder().decode(second)) }
        val refs = listOf(a, b).map { Attachment(it.name, it.path, "image/png", it.length()) }
        val access = object : PrivateModelAccess {
            override suspend fun request(provider: Provider, session: String, body: JSONObject): Request = error("Expected native image route")
            override suspend fun imageRequest(provider: Provider, session: String, request: Request): Request = request.newBuilder().header("Authorization", "Bearer edit-account").build()
            override suspend fun response(request: Request, response: Response) = Unit
            override suspend fun models() = emptyList<String>()
        }
        for (count in 1..2) MockWebServer().use { server ->
            server.enqueue(response())
            val provider = CodexModel("gpt-image-2.5-sunburst").provider().copy(baseUrl = server.url("/v1").toString())
            val result = ImageClient(privateModels = access).generate(provider, ImageSettings(quality = "max", size = "3840x2160"), "更改背景", refs.take(count),
                temporary.newFolder(), temporary.newFolder(), "s")
            assertEquals(1, result.images.size)
            val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
            assertEquals("/v1/images/edits", request.path); assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            assertEquals("Bearer edit-account", request.getHeader("Authorization")); assertTrue(body.getBoolean("stream"))
            assertEquals(1, body.getInt("n")); assertEquals("max", body.getString("quality")); assertEquals("3840x2160", body.getString("size"))
            val images = body.getJSONArray("images"); assertEquals(count, images.length())
            assertEquals("data:image/png;base64,$encoded", images.getJSONObject(0).getString("image_url"))
            if (count == 2) assertEquals("data:image/png;base64,$second", images.getJSONObject(1).getString("image_url"))
            assertFalse(body.has("image")); assertFalse(body.has("input")); assertEquals(1, server.requestCount)
        }
    }
    @Test fun imageValidationErrorsKeepTheReasonWithoutEchoingUploadedContentOrRetrying() = runBlocking {
        val full = """{"detail":[{"loc":["body","images"],"msg":"Expected JSON image references","type":"model_attributes_type","input":"PRIVATE_UPLOAD_BYTES"}]}"""
        val truncated = full.replace("PRIVATE_UPLOAD_BYTES", "PRIVATE_UPLOAD_BYTES".repeat(1000))
        for (body in listOf(full, truncated)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody(body))
            val error = runCatching { generate(server) }.exceptionOrNull()
            assertTrue(error is ImageGenerationFailure)
            assertTrue(error!!.message!!.contains("Expected JSON image references"))
            assertFalse(error.message!!.contains("PRIVATE_UPLOAD_BYTES")); assertEquals(1, server.requestCount)
        }
    }
    @Test fun imageErrorsAlsoRecognizeStringDetailsAndPlainTextRejections() = runBlocking {
        for (body in listOf("""{"detail":"JSON request body required"}""", "JSON request body required")) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(body))
            val error = runCatching { generate(server) }.exceptionOrNull()
            assertTrue(error is ImageGenerationFailure); assertTrue(error!!.message!!.contains("JSON request body required"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun aPartiallyCompletedImageBatchIsPreservedWithoutAnAutomaticReplay() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"result\":\"$encoded\"}}\n\n"))
            val progress = mutableListOf<Int>()
            val error = runCatching { ImageClient().generate(provider(server), ImageSettings(count = 2), "Two images", emptyList(),
                temporary.newFolder(), temporary.newFolder(), "s", onImages = { progress += it.size }) }.exceptionOrNull()
            assertTrue(error is ImageGenerationFailure)
            assertEquals(1, (error as ImageGenerationFailure).partial.size); assertEquals(listOf(1), progress)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun everyBatchSlotUsesAnIndependentRequestEvenWhenTheUpstreamReturnsIdenticalFiles() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(response()) }
            val progress = mutableListOf<Int>()
            val result = ImageClient().generate(provider(server), ImageSettings(count = 3), "Three images", emptyList(),
                temporary.newFolder(), temporary.newFolder(), "s", onImages = { progress += it.size })
            assertEquals(listOf(1, 2, 3), progress); assertEquals(3, result.images.size); assertEquals(3, server.requestCount)
            repeat(3) { assertEquals(1, JSONObject(server.takeRequest().body.readUtf8()).getInt("n")) }
        }
    }
    @Test fun failureOfTheSecondIndependentSlotPreservesTheFirstAndDoesNotRequestTheThird() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response()); server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"upstream unavailable\"}}"))
            val error = runCatching { generate(server, ImageSettings(count = 3)) }.exceptionOrNull()
            assertTrue(error is ImageGenerationFailure)
            val image = (error as ImageGenerationFailure).partial.single()
            assertArrayEquals(png, File(image.path).readBytes()); assertEquals(2, server.requestCount)
        }
    }
    @Test fun responsesBatchesAlsoStartOneRequestPerOutputSlot() = runBlocking {
        MockWebServer().use { server ->
            val final = """{"type":"response.completed","response":{"output":[{"type":"image_generation_call","result":"$encoded"}]}}"""
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $final\n\n")) }
            assertEquals(2, generate(server, ImageSettings(transport = "responses", count = 2)).images.size)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("/v1/responses", server.takeRequest().path) }
        }
    }
    @Test fun unsupportedImageRoute503IsReportedOnceAndNeverRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"model gpt-image-2.5-sunburst is only supported on /v1/images/generations and /v1/images/edits"}}"""))
            val result = runCatching { generate(server) }
            assertTrue(result.exceptionOrNull() is ImageGenerationFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("/v1/images/generations")); assertEquals(1, server.requestCount)
        }
    }
    @Test fun theChatClientAlsoDoesNotRetryDeterministicImageRouteErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"model is only supported on /v1/images/generations and /v1/images/edits"}}"""))
            val result = runCatching { ApiClient().stream(provider(server), "s", listOf(Message(sessionId = "s", role = "user", text = "draw"))) {} }
            assertTrue(result.exceptionOrNull() is ApiFailure); assertEquals(1, server.requestCount)
        }
    }
    @Test fun nativeChatImageGenerationSavesAttachmentsAndKeepsBase64OutOfHistory() = runBlocking {
        MockWebServer().use { server ->
            val item = """{"type":"image_generation_call","result":"$encoded","id":"img1"}"""
            val events = "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$item}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[$item]}}\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events))
            val directory = temporary.newFolder()
            val result = ApiClient(mediaDirectory = { directory }, temporaryDirectory = temporary.newFolder()).stream(provider(server).copy(model = "gpt-6.1-sol"), "s", listOf(Message(sessionId = "s", role = "user", text = "draw"))) {}
            assertEquals(1, result.attachments.size); assertArrayEquals(png, File(result.attachments.single().path).readBytes())
            assertFalse(result.raw.contains(encoded)); assertTrue(result.raw.contains("image_generation_call")); assertTrue(result.text.isEmpty())
        }
    }
    @Test fun receivedImagesSurviveAnIncompleteNativeResponse() = runBlocking {
        MockWebServer().use { server ->
            val item = """{"type":"response.output_item.done","output_index":0,"item":{"type":"image_generation_call","result":"$encoded"}}"""
            val incomplete = """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}}"""
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $item\n\ndata: $incomplete\n\n"))
            val result = runCatching { ApiClient(mediaDirectory = { temporary.root }, temporaryDirectory = temporary.newFolder()).stream(provider(server), "s", listOf(Message(sessionId = "s", role = "user", text = "draw"))) {} }
            val error = result.exceptionOrNull() as ModelIncomplete
            assertEquals(1, error.partial.attachments.size); assertEquals(1, server.requestCount)
        }
    }
    @Test fun missingOrCorruptImageIsAnErrorAndDoesNotTriggerAnotherChargedRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"data\":[]}"))
            assertTrue(runCatching { generate(server) }.exceptionOrNull() is ImageGenerationFailure)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun resultUrlsReceiveNoApiOrCustomCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":[{"url":"${server.url("/image.png")}"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(png)))
            assertEquals(1, generate(server).images.size)
            server.takeRequest(); val download = server.takeRequest()
            assertNull(download.getHeader("Authorization")); assertNull(download.getHeader("X-Proxy-Key"))
        }
    }
    @Test fun cancellationAfterHeadersClosesTheBlockedImageReadPromptly() = runBlocking {
        MockWebServer().use { server ->
            val headers = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain -> chain.proceed(chain.request()).also { headers.complete(Unit) } }.build()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"data\":[" + " ".repeat(100_000)).throttleBody(1024, 1, TimeUnit.SECONDS))
            val task = launch(Dispatchers.IO) { generate(server, client = ImageClient(client = client)) }
            withTimeout(5000) { headers.await() }
            withTimeout(3000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled); assertEquals(1, server.requestCount)
        }
    }
}
