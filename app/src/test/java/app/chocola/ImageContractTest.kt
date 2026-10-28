package app.chocola

import app.chocola.agent.Tools
import app.chocola.api.*
import app.chocola.data.*
import app.chocola.data.Protocol
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class ImageContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private val encoded = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6S7sAAAAASUVORK5CYII="
    private val png get() = Base64.getDecoder().decode(encoded)

    @Test fun responsesRestrictsExecutionToTheAdvertisedToolsRatherThanProxyInjectedImageTools() {
        val schemas = Tools.schemas()
        val request = ProtocolCodec.request(Provider(protocol = Protocol.RESPONSES, model = "gpt-6.1-sol"), "s",
            listOf(Message(sessionId = "s", role = "user", text = "绘图")), schemas)
        val choice = request.getJSONObject("tool_choice")
        assertEquals("allowed_tools", choice.getString("type")); assertEquals("auto", choice.getString("mode"))
        assertEquals(schemas.objects().map { it.getString("name") }, choice.getJSONArray("tools").objects().map { it.getString("name") })
        assertTrue(choice.getJSONArray("tools").objects().all { it.getString("type") == "function" })
        assertTrue(choice.toString().contains("generate_image")); assertFalse(choice.toString().contains("image_generation"))
        val disabled = ProtocolCodec.request(Provider(protocol = Protocol.RESPONSES, extraBody = "{\"tool_choice\":\"none\"}"), "s", emptyList(), schemas)
        assertEquals("none", disabled.getString("tool_choice"))
    }
    @Test fun auxiliaryTextRequestsExplicitlyDisableInjectedTools() {
        val request = ProtocolCodec.request(Provider(protocol = Protocol.RESPONSES), "title", listOf(Message(sessionId = "s", role = "user", text = "draw a cat")))
        assertEquals("none", request.getString("tool_choice"))
        for (protocol in listOf(Protocol.CHAT, Protocol.ANTHROPIC)) assertFalse(ProtocolCodec.request(Provider(protocol = protocol), "s", emptyList(), Tools.schemas()).has("tool_choice"))
    }
    @Test fun serverAutoMetadataCannotEraseTheRequested4kOrActualPngDimensions() {
        val media = GeneratedMedia(temporary.newFolder())
        val text = """{"model":"gpt-image-2-codex","size":"auto","quality":"medium","data":[{"b64_json":"$encoded"}]}"""
        MediaJson(media).parse { text.byteInputStream() }
        val image = media.outputs(ImageOutput(route = "images", requestedModel = "gpt-image-2.5-sunburst", requestedSize = "2160x3840", requestedQuality = "max")).single()
        assertEquals(1, image.width); assertEquals(1, image.height)
        assertEquals("2160x3840", image.output!!.requestedSize); assertEquals("auto", image.output!!.reportedSize)
        assertEquals("max", image.output!!.requestedQuality); assertEquals("medium", image.output!!.reportedQuality)
        assertEquals("gpt-image-2-codex", image.output!!.reportedModel); assertTrue(image.imageSizeMismatch)
        assertTrue(image.imageWarning.contains("2160×3840")); assertArrayEquals(png, File(image.path).readBytes())
    }
    @Test fun responseImageMetadataComesFromTheImageCallRatherThanTheChatModel() {
        val media = GeneratedMedia(temporary.newFolder())
        val text = """{"type":"response.completed","response":{"model":"gpt-6.1-sol","tools":[{"type":"image_generation","model":"gpt-image-2.5-sunburst","size":"2160x3840"}],"output":[{"type":"image_generation_call","result":"$encoded","model":"gpt-image-2-codex","size":"auto","quality":"auto"}]}}"""
        MediaJson(media).parse { text.byteInputStream() }
        val image = media.outputs().single()
        assertEquals("gpt-image-2-codex", image.output!!.reportedModel); assertEquals("auto", image.output!!.reportedSize)
        assertEquals("", image.output!!.requestedModel); assertEquals("native", image.output!!.route)
    }
    @Test fun missingServerMetadataStaysUnknownAndDoesNotPretendToConfirmTheRequestedModel() {
        val media = GeneratedMedia(temporary.newFolder())
        MediaJson(media).parse { """{"data":[{"b64_json":"$encoded"}]}""".byteInputStream() }
        val image = media.outputs(ImageOutput(requestedModel = "gpt-image-2.5-sunburst", requestedSize = "auto")).single()
        assertEquals("", image.output!!.reportedModel); assertEquals("", image.output!!.reportedSize)
        assertFalse(image.imageSizeMismatch)
    }
    @Test fun exactLargerSmallerAndUnknownDimensionsDoNotAlterOrUpscaleTheFile() {
        val image = Attachment("a.png", "/workspace/generated/a.png", "image/png", 100, 2160, 3840, ImageOutput(requestedSize = "2160x3840"))
        assertFalse(image.imageSizeMismatch)
        assertTrue(image.copy(width = 940, height = 1673).imageSizeMismatch)
        assertTrue(image.copy(output = ImageOutput(requestedSize = "1024x1536")).imageSizeMismatch)
        assertFalse(image.copy(output = ImageOutput(requestedSize = "auto")).imageSizeMismatch)
        assertFalse(image.copy(width = 0, height = 0).imageSizeMismatch)
        assertEquals(Attachment.from(image.json()), image)
        assertNull(Attachment.from(image.json().apply { remove("output") }).output)
    }
    @Test fun imageDimensionMismatchKeepsTheOriginalAndDoesNotIssueAnotherGeneration() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"size":"auto","quality":"medium","data":[{"b64_json":"$encoded"}]}"""))
            val provider = Provider(baseUrl = server.url("/v1").toString(), model = "gpt-image-2.5-sunburst")
            val image = ImageClient().generate(provider, ImageSettings(size = "2160x3840", quality = "max"), "test", emptyList(), temporary.newFolder(), temporary.newFolder(), "s").images.single()
            assertTrue(image.imageSizeMismatch); assertEquals("medium", image.output!!.reportedQuality)
            assertArrayEquals(png, File(image.path).readBytes()); assertEquals(1, server.requestCount)
            assertEquals("2160x3840", JSONObject(server.takeRequest().body.readUtf8()).getString("size"))
        }
    }
    @Test fun nativeTransportRecordsActualImageMetadataWithoutRetainingBase64InHistory() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"completed","model":"gpt-6.1-sol","output":[{"type":"image_generation_call","model":"gpt-image-2-codex","size":"auto","quality":"auto","result":"$encoded"}]}"""))
            val result = ApiClient(mediaDirectory = { temporary.root }, temporaryDirectory = temporary.newFolder()).stream(
                Provider(baseUrl = server.url("/v1").toString(), protocol = Protocol.RESPONSES, model = "gpt-6.1-sol"), "s", listOf(Message(sessionId = "s", role = "user", text = "draw"))) {}
            assertEquals("gpt-image-2-codex", result.attachments.single().output!!.reportedModel)
            assertFalse(result.raw.contains(encoded)); assertEquals(1, server.requestCount)
        }
    }
}
