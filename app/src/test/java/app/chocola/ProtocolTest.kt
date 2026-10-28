package app.chocola

import app.chocola.api.*
import app.chocola.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun message(role: String, text: String = "", calls: List<ToolCall> = emptyList(), toolCallId: String = "") = Message(sessionId = "s", role = role, text = text, calls = calls, toolCallId = toolCallId)
    @Test fun fullEndpointDoesNotDuplicateSuffix() {
        for ((protocol, suffix) in listOf(Protocol.CHAT to "/chat/completions", Protocol.RESPONSES to "/responses", Protocol.ANTHROPIC to "/messages")) {
            val p = Provider(protocol = protocol, baseUrl = "https://proxy.example/v1$suffix/")
            assertEquals("https://proxy.example/v1$suffix", ProtocolCodec.endpoint(p))
            assertEquals("https://proxy.example/v1/models", ProtocolCodec.endpoint(p, true))
        }
    }
    @Test fun anthropicGroupsParallelToolResultsInOneUserMessage() {
        val calls = listOf(ToolCall("a", "read_file", "{\"path\":\"a\"}"), ToolCall("b", "read_file", "{\"path\":\"b\"}"))
        val j = ProtocolCodec.request(Provider(protocol = Protocol.ANTHROPIC), "system", listOf(message("user", "read"), message("assistant", calls = calls), message("tool", "A", toolCallId = "a"), message("tool", "B", toolCallId = "b")))
        val m = j.getJSONArray("messages")
        assertEquals(3, m.length())
        assertEquals("user", m.getJSONObject(2).getString("role"))
        assertEquals(2, m.getJSONObject(2).getJSONArray("content").length())
        assertEquals("b", m.getJSONObject(2).getJSONArray("content").getJSONObject(1).getString("tool_use_id"))
    }
    @Test fun responsesRetainsReasoningAndFunctionCallIds() {
        val raw = JSONArray().put(JSONObject().put("type", "reasoning").put("encrypted_content", "opaque"))
            .put(JSONObject().put("type", "function_call").put("call_id", "call_1").put("name", "read_file").put("arguments", "{}"))
        val history = listOf(message("user", "read"), message("assistant").copy(rawOutput = raw.toString()), message("tool", "result", toolCallId = "call_1"))
        val j = ProtocolCodec.request(Provider(protocol = Protocol.RESPONSES), "system", history)
        assertEquals("opaque", j.getJSONArray("input").getJSONObject(1).getString("encrypted_content"))
        assertEquals("call_1", j.getJSONArray("input").getJSONObject(3).getString("call_id"))
        assertFalse(j.getBoolean("store"))
    }
    @Test fun optionalTemperatureIsOmittedForReasoningModels() {
        Protocol.entries.forEach { protocol ->
            assertFalse(ProtocolCodec.request(Provider(protocol = protocol), "s", listOf(message("user", "hi"))).has("temperature"))
        }
    }
    @Test fun chatPreservesAssistantToolCallAndResultPair() {
        val call = ToolCall("x", "write_file", "{\"path\":\"a\",\"content\":\"中文\"}")
        val j = ProtocolCodec.request(Provider(), "s", listOf(message("user", "write"), message("assistant", calls = listOf(call)), message("tool", "ok", toolCallId = "x")))
        val m = j.getJSONArray("messages")
        assertTrue(m.getJSONObject(2).isNull("content"))
        assertEquals("x", m.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("x", m.getJSONObject(3).getString("tool_call_id"))
    }
    @Test fun streamingChatAssemblesFragmentedToolJson() {
        val s = StreamAccumulator(Protocol.CHAT)
        s.accept("", """{"choices":[{"delta":{"content":"你好","tool_calls":[{"index":0,"id":"x","function":{"name":"write_file","arguments":"{\"path\":"}}]}}]}""")
        s.accept("", """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a\",\"content\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}""")
        val r = s.result(); assertEquals("你好", r.text); assertEquals("a", JSONObject(r.calls.single().arguments).getString("path"))
    }
    @Test fun streamingAnthropicMaintainsBlockIndices() {
        val s = StreamAccumulator(Protocol.ANTHROPIC)
        s.accept("content_block_start", """{"index":1,"content_block":{"type":"tool_use","id":"tool1","name":"run_shell","input":{}}}""")
        s.accept("content_block_delta", """{"index":0,"delta":{"type":"text_delta","text":"检查文件"}}""")
        s.accept("content_block_delta", """{"index":1,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"ls\"}"}}""")
        s.accept("message_stop", "{}")
        assertEquals("检查文件", s.result().text); assertEquals("ls", JSONObject(s.result().calls.single().arguments).getString("command"))
    }
    @Test fun streamingResponsesUsesCompletedOutput() {
        val s = StreamAccumulator(Protocol.RESPONSES)
        s.accept("", """{"type":"response.output_text.delta","delta":"早期片段"}""")
        s.accept("", """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"最终文本"}]}],"usage":{"input_tokens":9,"output_tokens":3}}}""")
        assertEquals("最终文本", s.result().text); assertEquals(9, s.result().inputTokens)
    }
    @Test(expected = IllegalStateException::class) fun incompleteStreamDoesNotBecomeSuccess() {
        val s = StreamAccumulator(Protocol.CHAT); s.accept("", """{"choices":[{"delta":{"content":"partial"}}]}"""); s.result()
    }
    @Test(expected = IllegalStateException::class) fun inStreamErrorIsPropagated() {
        StreamAccumulator(Protocol.ANTHROPIC).accept("error", """{"error":{"type":"overloaded_error","message":"busy"}}""")
    }
    @Test fun jsonFallbackDecodesAllProtocols() {
        val chat = ProtocolCodec.decodeJson(Protocol.CHAT, JSONObject("""{"choices":[{"message":{"content":"chat"}}]}"""))
        val anthropic = ProtocolCodec.decodeJson(Protocol.ANTHROPIC, JSONObject("""{"content":[{"type":"text","text":"claude"}]}"""))
        val responses = ProtocolCodec.decodeJson(Protocol.RESPONSES, JSONObject("""{"output":[{"type":"message","content":[{"type":"output_text","text":"response"}]}]}"""))
        assertEquals(listOf("chat", "claude", "response"), listOf(chat.text, anthropic.text, responses.text))
    }
    @Test fun chatPreservesReasoningAndGeminiToolSignaturesAndLateUsage() {
        val s = StreamAccumulator(Protocol.CHAT)
        s.accept("", """{"choices":[{"delta":{"reasoning_content":"先检查","tool_calls":[{"index":0,"id":"call-1","extra_content":{"google":{"thought_signature":"opaque-signature"}},"function":{"name":"read_file","arguments":"{\"path\":"}}]}}]}""")
        s.accept("", """{"choices":[{"delta":{"reasoning_content":"文件","tool_calls":[{"index":0,"function":{"arguments":"\"a\"}"}}]},"finish_reason":"tool_calls"}]}""")
        s.accept("", """{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":45}}""")
        s.accept("", "[DONE]")
        val result = s.result()
        val m = message("assistant", calls = result.calls).copy(rawOutput = result.raw, model = "gemini-4-argon")
        val body = ProtocolCodec.request(Provider(model = m.model), "system", listOf(m))
        val raw = body.getJSONArray("messages").getJSONObject(1)
        assertEquals("先检查文件", raw.getString("reasoning_content"))
        assertEquals("opaque-signature", raw.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
        assertEquals(120, result.inputTokens); assertEquals(45, result.outputTokens)
    }
    @Test fun anthropicThinkingSignaturesStayIntactForTheSameModelAndAreDroppedOnModelSwitch() {
        val s = StreamAccumulator(Protocol.ANTHROPIC)
        s.accept("content_block_start", """{"index":0,"content_block":{"type":"thinking","thinking":""}}""")
        s.accept("content_block_delta", """{"index":0,"delta":{"type":"thinking_delta","thinking":"检查依赖"}}""")
        s.accept("content_block_delta", """{"index":0,"delta":{"type":"signature_delta","signature":"signed-"}}""")
        s.accept("content_block_delta", """{"index":0,"delta":{"type":"signature_delta","signature":"opaque"}}""")
        s.accept("content_block_start", """{"index":1,"content_block":{"type":"tool_use","id":"t","name":"read_file","input":{}}}""")
        s.accept("content_block_delta", """{"index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"file\"}"}}""")
        s.accept("message_delta", """{"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":72}}""")
        s.accept("message_stop", "{}")
        val result = s.result()
        val m = message("assistant", calls = result.calls).copy(rawOutput = result.raw, model = "claude-sonnet-5-5")
        val p = Provider(protocol = Protocol.ANTHROPIC, model = m.model)
        val content = ProtocolCodec.request(p, "s", listOf(m)).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("signed-opaque", content.getJSONObject(0).getString("signature"))
        assertEquals("检查依赖", content.getJSONObject(0).getString("thinking"))
        val switched = ProtocolCodec.request(p.copy(model = "claude-opus-5-5"), "s", listOf(m)).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(1, switched.length()); assertEquals("tool_use", switched.getJSONObject(0).getString("type"))
        assertEquals("file", switched.getJSONObject(0).getJSONObject("input").getString("path"))
    }
    @Test fun responsesIncompleteCarriesItsBudgetReasonAndPartialTextWithoutExecutableTools() {
        val s = StreamAccumulator(Protocol.RESPONSES)
        s.accept("", """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"message","content":[{"type":"output_text","text":"已收到的内容"}]}],"usage":{"input_tokens":500,"output_tokens":2048}}}""")
        try { s.result(); fail("Expected a typed incomplete result") } catch (e: ModelIncomplete) {
            assertEquals("max_output_tokens", e.reason); assertEquals("已收到的内容", e.partial.text)
            assertEquals(2048, e.partial.outputTokens); assertTrue(e.partial.calls.isEmpty())
        }
    }
    @Test fun jsonLengthStopsAreErrorsAcrossAllThreeProtocols() {
        val fixtures = listOf(
            Protocol.CHAT to """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""",
            Protocol.ANTHROPIC to """{"content":[{"type":"text","text":"partial"}],"stop_reason":"max_tokens"}""",
            Protocol.RESPONSES to """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]}]}""")
        fixtures.forEach { (protocol, data) ->
            try { ProtocolCodec.decodeJson(protocol, JSONObject(data)); fail("Expected $protocol to reject truncation") }
            catch (e: ModelIncomplete) { assertEquals("partial", e.partial.text) }
        }
    }
    @Test fun oldDeepseekToolHistoryGetsAnEmptyReasoningFieldForMigration() {
        val tool = ToolCall("call", "read_file", "{}")
        val p = Provider(model = "deepseek-flash")
        val tools = JSONArray().put(JSONObject().put("name", "read_file"))
        val body = ProtocolCodec.request(p, "s", listOf(message("assistant", calls = listOf(tool))), tools)
        assertEquals("", body.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"))
    }
    @Test fun anthropicCompatibleBasesIncludeTheirRequiredVersionPath() {
        val p = Provider(protocol = Protocol.ANTHROPIC, baseUrl = "https://api.deepseek.com/anthropic")
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", ProtocolCodec.endpoint(p))
    }
}
