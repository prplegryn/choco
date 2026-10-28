package app.chocola

import app.chocola.api.*
import app.chocola.data.*
import app.chocola.data.Protocol
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StreamActivityTest {
    @Test fun reasoningAndToolPreparationAreDistinctFromVisibleOutputWithoutRetainingPayloads() {
        val tracker = StreamActivity(Protocol.RESPONSES)
        tracker.observe("", JSONObject("""{"type":"response.reasoning_text.delta","delta":"private reasoning"}"""))
        assertEquals(ReplyPhase.THINKING, tracker.phase)
        assertEquals(17L, tracker.fields()["reasoningDeltaChars"])
        assertEquals(0L, tracker.fields()["textDeltaChars"])
        tracker.observe("", JSONObject("""{"type":"response.output_item.added","item":{"type":"function_call","name":"read_file"}}"""))
        assertEquals(ReplyPhase.TOOL, tracker.phase)
        tracker.observe("", JSONObject("""{"type":"response.function_call_arguments.delta","delta":"secret arguments"}"""))
        assertEquals(16L, tracker.fields()["toolArgumentDeltaChars"])
        tracker.observe("", JSONObject("""{"type":"response.output_text.delta","delta":"正文"}"""))
        assertEquals(ReplyPhase.TEXT, tracker.phase)
        assertEquals(2L, tracker.fields()["textDeltaChars"])
        val fields = JSONObject(tracker.fields()).toString()
        assertFalse(fields.contains("private reasoning"))
        assertFalse(fields.contains("secret arguments"))
        assertFalse(fields.contains("read_file"))
        assertFalse(fields.contains("正文"))
    }

    @Test fun chatAndAnthropicReasoningAreCountedWhileKeepaliveDoesNotInventProgress() {
        val chat = StreamActivity(Protocol.CHAT)
        chat.observe("", JSONObject("""{"choices":[{"delta":{"reasoning_content":"思考"}}]}"""))
        assertEquals(ReplyPhase.THINKING, chat.phase)
        chat.observe("", JSONObject("""{"choices":[{"delta":{}}]}"""))
        assertEquals(ReplyPhase.THINKING, chat.phase)
        assertEquals(2L, chat.fields()["reasoningDeltaChars"])
        val claude = StreamActivity(Protocol.ANTHROPIC)
        claude.observe("content_block_delta", JSONObject("""{"delta":{"type":"thinking_delta","thinking":"hidden"}}"""))
        assertEquals(ReplyPhase.THINKING, claude.phase)
        claude.observe("ping", JSONObject())
        assertEquals(ReplyPhase.THINKING, claude.phase)
        assertEquals(6L, claude.fields()["reasoningDeltaChars"])
        claude.observe("content_block_delta", JSONObject("""{"delta":{"type":"input_json_delta","partial_json":"{}"}}"""))
        assertEquals(ReplyPhase.TOOL, claude.phase)
    }

    @Test fun unknownEventsAreBoundedAndCannotWriteArbitraryTypeStringsToLogs() {
        val tracker = StreamActivity(Protocol.RESPONSES)
        repeat(100) { tracker.observe("", JSONObject().put("type", "private-$it").put("delta", "secret")) }
        assertEquals(ReplyPhase.WAITING, tracker.phase)
        assertEquals(100L, tracker.fields()["unknownEvents"])
        assertEquals("unknown", tracker.fields()["lastEventType"])
        assertEquals(mapOf("unknown" to 100L), tracker.fields()["eventTypes"])
    }

    @Test fun wrongSuccessfulProtocolFailsInsteadOfWaitingUntilTheEntireGenerationEnds() = runBlocking {
        MockWebServer().use { server ->
            val data = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\"}}]}\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(data))
            val provider = Provider(baseUrl = server.url("/v1").toString(), protocol = Protocol.RESPONSES, model = "glm-5.3")
            val error = runCatching { ApiClient().stream(provider, "s", listOf(Message(sessionId = "s", role = "user", text = "hello"))) {} }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message.orEmpty().contains("流格式"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun thinkingStreamsDoNotPersistOrDispatchTheSameEmptyBodyRepeatedly() = runBlocking {
        MockWebServer().use { server ->
            val events = listOf(
                JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("reasoning_content", "hidden")))),
                JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "done")))),
                JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "stop")))
            )
            val body = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            val phases = mutableListOf<ReplyPhase>()
            val texts = mutableListOf<String>()
            val provider = Provider(baseUrl = server.url("/v1").toString(), model = "glm-5.3")
            val result = ApiClient().stream(provider, "s", listOf(Message(sessionId = "s", role = "user", text = "hello")), onPhase = { phases += it }) { texts += it }
            assertEquals(listOf(ReplyPhase.THINKING, ReplyPhase.TEXT), phases)
            assertEquals(listOf("done"), texts)
            assertEquals("done", result.text)
            assertTrue(JSONObject(result.raw).has("reasoning_content"))
        }
    }

    @Test fun bigModelUsesItsDocumentedProtocolAddressesAndToolChoiceWithoutChangingProxiesOrCodingPlans() {
        val provider = Provider(baseUrl = "https://open.bigmodel.cn/api/paas/v4", protocol = Protocol.RESPONSES, model = "glm-5.3")
        assertEquals("https://open.bigmodel.cn/api/v1/responses", ProtocolCodec.endpoint(provider))
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", ProtocolCodec.endpoint(provider.copy(baseUrl = "https://open.bigmodel.cn/api/v1", protocol = Protocol.CHAT)))
        assertEquals("https://proxy.example/v4/responses", ProtocolCodec.endpoint(provider.copy(baseUrl = "https://proxy.example/v4")))
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4/responses", ProtocolCodec.endpoint(provider.copy(baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4")))
        val tools = JSONArray().put(JSONObject("""{"name":"read_file","description":"read","parameters":{"type":"object","properties":{}}}"""))
        val body = ProtocolCodec.request(provider, "s", emptyList(), tools)
        assertEquals("auto", body.getString("tool_choice"))
        assertEquals("none", ProtocolCodec.request(provider.copy(extraBody = """{"tool_choice":"none"}"""), "s", emptyList(), tools).getString("tool_choice"))
    }

    @Test fun bigModelSingleContentObjectAndStandardContentArrayBothRetainTheFinalAnswer() {
        val single = JSONObject("""{"output":[{"type":"message","content":{"type":"output_text","text":"完成"}}]}""")
        val array = JSONObject("""{"output":[{"type":"message","content":[{"type":"output_text","text":"完成"}]}]}""")
        assertEquals("完成", ProtocolCodec.decodeJson(Protocol.RESPONSES, single).text)
        assertEquals("完成", ProtocolCodec.decodeJson(Protocol.RESPONSES, array).text)
    }

    @Test fun nativeImagesAreNotMisreportedAsTextOrReasoning() {
        val tracker = StreamActivity(Protocol.RESPONSES)
        tracker.observe("", JSONObject("""{"type":"response.image_generation_call.in_progress"}"""))
        assertEquals(ReplyPhase.IMAGE, tracker.phase)
        assertEquals(0L, tracker.fields()["reasoningDeltaChars"])
        assertEquals(0L, tracker.fields()["textDeltaChars"])
    }

    @Test fun reasoningTokenUsageSurvivesBudgetFailureAndUnknownIsNotInventedAsZero() {
        val stream = StreamAccumulator(Protocol.RESPONSES)
        stream.accept("", """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],"usage":{"input_tokens":5416,"output_tokens":32768,"output_tokens_details":{"reasoning_tokens":32768}}}}""")
        val error = runCatching { stream.result() }.exceptionOrNull() as ModelIncomplete
        assertEquals(32768, error.partial.reasoningTokens)
        assertEquals(32768, error.partial.outputTokens)
        assertEquals("", error.partial.text)
        assertNull(ProtocolCodec.decodeJson(Protocol.RESPONSES, JSONObject("""{"output":[]}""")).reasoningTokens)
        val chat = ProtocolCodec.decodeJson(Protocol.CHAT, JSONObject("""{"choices":[{"message":{"content":"ok"}}],"usage":{"completion_tokens":100,"completion_tokens_details":{"reasoning_tokens":98}}}"""))
        assertEquals(98, chat.reasoningTokens)
    }
}
