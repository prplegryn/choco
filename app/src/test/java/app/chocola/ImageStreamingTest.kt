package app.chocola

import app.chocola.api.*
import app.chocola.data.*
import kotlinx.coroutines.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class ImageStreamingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun bytes(size: Int = 64) = ByteArray(size) { (it * 31).toByte() }.apply {
        byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10).copyInto(this)
        byteArrayOf(0, 0, 4, 0, 0, 0, 6, 0).copyInto(this, 16)
    }
    private fun base64(size: Int = 64) = Base64.getEncoder().encodeToString(bytes(size))
    private fun media() = GeneratedMedia(temporary.newFolder())
    private fun MediaJson.json(text: String) = parse { text.byteInputStream() }

    @Test fun hugeImageEventSpillsAndDecodesWithoutTheOldTwoMillionByteLimit() = runBlocking {
        val encoded = base64(2_100_000)
        val input = Buffer().writeUtf8("data: {\"item\":{\"result\":\"$encoded\",\"type\":\"image_generation_call\"},\"type\":\"response.output_item.done\"}\r\n\r\n")
        val spool = temporary.newFolder(); val saved = media()
        SseReader(input, spool).read({ false }) { _, payload ->
            assertTrue(payload.spilled); assertTrue(payload.size > 2_000_000)
            val json = MediaJson(saved).parse(payload::input)
            assertTrue(json.getJSONObject("item").isNull("result"))
            assertFalse(json.toString().contains(encoded.take(64)))
        }
        assertArrayEquals(bytes(2_100_000), File(saved.attachments.single().path).readBytes())
        assertEquals(1024, saved.attachments.single().width); assertEquals(1536, saved.attachments.single().height)
        assertTrue(spool.listFiles().orEmpty().isEmpty())
    }
    @Test fun completedOutputAndItemDoneDeduplicateTheSameImageRegardlessOfFieldOrder() {
        val saved = media(); val decoder = MediaJson(saved); val encoded = base64()
        decoder.json("""{"item":{"result":"$encoded","type":"image_generation_call"}}""")
        decoder.json("""{"response":{"output":[{"type":"image_generation_call","result":"$encoded"}]}}""")
        assertEquals(1, saved.attachments.size)
        assertEquals(1, File(saved.attachments.single().path).parentFile!!.listFiles()!!.size)
    }
    @Test fun bothKindsOfPartialImageRemainTransientAndNeverBecomeFinalAttachments() {
        val saved = media(); val decoder = MediaJson(saved)
        assertTrue(decoder.json("""{"b64_json":"${base64()}","type":"image_generation.partial_image"}""").isNull("b64_json"))
        decoder.json("""{"type":"response.image_generation_call.partial_image","partial_image_b64":"${base64()}"}""")
        assertTrue(saved.attachments.isEmpty())
    }
    @Test fun framingHandlesBomCommentsAllLineEndingsMultilineAndEof() = runBlocking {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val text = "\uFEFF: ping${ending}event: test${ending}data: 你好${ending}data: 世界${ending}${ending}data: tail"
            val events = mutableListOf<Pair<String, String>>()
            SseReader(Buffer().writeUtf8(text), temporary.newFolder()).read({ false }) { event, data -> events += event to data.input().use { it.reader().readText() } }
            assertEquals(listOf("test" to "你好\n世界", "" to "tail"), events)
        }
    }
    @Test fun spillingFilesAreRemovedWhenConsumerFailsOrCancels() = runBlocking {
        for (cancel in listOf(false, true)) {
            val spool = temporary.newFolder()
            val result = runCatching { SseReader(Buffer().writeUtf8("data: " + "x".repeat(70_000) + "\n\n"), spool).read({ false }) { _, _ ->
                if (cancel) throw CancellationException("stopped") else error("consumer failed")
            } }
            assertTrue(result.isFailure); assertTrue(spool.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun sizeGuardStopsBeforeUnboundedDiskOrHeapGrowth() = runBlocking {
        val spool = temporary.newFolder()
        val result = runCatching { SseReader(Buffer().writeUtf8("data: " + "x".repeat(10_000) + "\n\n"), spool, 4096).read({ false }) { _, _ -> fail("oversize event delivered") } }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException); assertTrue(spool.listFiles().orEmpty().isEmpty())
    }
    @Test fun invalidBinaryDoesNotPersistAnImageOrLeakItsPayloadIntoTheError() {
        val directory = temporary.newFolder(); val secret = "this-is-not-an-image"
        val result = runCatching { MediaJson(GeneratedMedia(directory)).json("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(secret.toByteArray())}"}]}""") }
        assertTrue(result.isFailure); assertFalse(result.exceptionOrNull()?.message.orEmpty().contains(secret))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun generatedImagesKeepReasoningButNeverReplayTheImageGenerationOutputAsInput() {
        val raw = """[{"type":"reasoning","encrypted_content":"opaque"},{"type":"image_generation_call","id":"image1","result":null}]"""
        val attachment = Attachment("image.png", "/private/workspaces/s/generated/image.png", "image/png", 64, 1024, 1536)
        val request = ProtocolCodec.request(Provider(protocol = Protocol.RESPONSES), "s", listOf(Message(sessionId = "s", role = "assistant", rawOutput = raw, attachments = listOf(attachment))))
        val input = request.getJSONArray("input").objects()
        assertEquals("reasoning", input.first().getString("type")); assertTrue(input.last().getString("content").contains("/workspace/generated/image.png"))
        assertFalse(request.toString().contains("image_generation_call"))
    }
    @Test fun completedEnvelopeMayOmitItemsAlreadyDeliveredInDoneEvents() {
        val stream = StreamAccumulator(Protocol.RESPONSES)
        stream.accept("", """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"完成"}]}}""")
        stream.accept("", """{"type":"response.completed","response":{"status":"completed","output":[]}}""")
        assertEquals("完成", stream.result().text)
    }
}
