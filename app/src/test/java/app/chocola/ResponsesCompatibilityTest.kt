package app.chocola

import app.chocola.agent.Tools
import app.chocola.api.ApiClient
import app.chocola.api.ProtocolCodec
import app.chocola.data.*
import app.chocola.data.Protocol
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesCompatibilityTest {
    private fun provider(model: String) = Provider(protocol = Protocol.RESPONSES, model = model)
    private val user get() = listOf(Message(sessionId = "s", role = "user", text = "检查项目文件"))
    private fun jsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }

    @Test fun compatibleProvidersUseAutoAndRetainEveryAdvertisedFunctionSchema() {
        val schemas = Tools.schemas()
        for (model in listOf("deepseek-flash", "deepseek-v4-pro", "glm-5.3", "qwen3.8-max", "claude-sonnet-5-5", "custom-model")) {
            val body = ProtocolCodec.request(provider(model), "s", user, schemas)
            assertEquals(model, "auto", body.get("tool_choice"))
            assertEquals(schemas.length(), body.getJSONArray("tools").length())
            body.getJSONArray("tools").objects().zip(schemas.objects()).forEach { (wire, schema) ->
                assertEquals("function", wire.getString("type"))
                assertEquals(schema.getString("name"), wire.getString("name"))
                assertEquals(jsonValue(schema.getJSONObject("parameters")), jsonValue(wire.getJSONObject("parameters")))
            }
        }
    }

    @Test fun officialCompatibleEndpointsTakePrecedenceOverAMisclassifiedModelFamily() {
        for (endpoint in listOf("https://api.deepseek.com", "https://api.deepseek.com/v1", "https://open.bigmodel.cn/api/v1", "https://api.z.ai/api/paas/v4")) {
            val p = provider("gpt-alias").copy(baseUrl = endpoint, family = "openai")
            assertEquals(endpoint, "auto", ProtocolCodec.request(p, "s", user, Tools.schemas()).get("tool_choice"))
        }
    }

    @Test fun openAIAndCodexProxiesKeepTheFunctionAllowlistAndDoNotEnableInjectedNativeImages() {
        for (endpoint in listOf("https://api.openai.com/v1", "http://127.0.0.1:8317/v1", "https://proxy.example/v1")) {
            val body = ProtocolCodec.request(provider("gpt-6.1-sol").copy(baseUrl = endpoint), "s", user, Tools.schemas())
            val choice = body.getJSONObject("tool_choice")
            assertEquals("allowed_tools", choice.getString("type"))
            val names = choice.getJSONArray("tools").objects().map { it.getString("name") }
            assertEquals(Tools.schemas().objects().map { it.getString("name") }, names)
            assertTrue("generate_image" in names)
            assertFalse("image_generation" in names)
        }
    }

    @Test fun auxiliaryCallsAndExplicitlyDisabledToolsStayDisabledAcrossAdapters() {
        for (model in listOf("deepseek-flash", "glm-5.3", "gpt-6.1-sol", "custom-model")) {
            val p = provider(model)
            assertEquals("none", ProtocolCodec.request(p, "title", user).getString("tool_choice"))
            assertFalse(ProtocolCodec.request(p, "title", user).has("tools"))
            assertEquals("none", ProtocolCodec.request(p.copy(extraBody = """{"tool_choice":"none"}"""), "s", user, Tools.schemas()).getString("tool_choice"))
        }
    }

    @Test fun deepSeekResponsesRoundTripUsesAutoAndPreservesReasoningAndToolResultPairing() = runBlocking {
        MockWebServer().use { server ->
            val first = """{"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","id":"r1","content":[{"type":"reasoning_text","text":"Inspect files first."}]},{"type":"function_call","id":"fc1","call_id":"call1","name":"list_files","arguments":"{\"path\":\".\"}"}],"usage":{"input_tokens":100,"output_tokens":30,"output_tokens_details":{"reasoning_tokens":15}}}}"""
            val second = """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"文件已检查。"}]}],"usage":{"input_tokens":150,"output_tokens":10}}}"""
            for (event in listOf(first, second)) server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\n"))
            val p = provider("deepseek-flash").copy(baseUrl = server.url("/").toString(), reasoningEffort = "low", thinking = "enabled", maxTokens = 32768)
            val api = ApiClient()
            val result = api.stream(p, "s", user, Tools.schemas()) {}
            assertEquals("list_files", result.calls.single().name)
            assertEquals(15, result.reasoningTokens ?: -1)
            val history = user + Message(sessionId = "s", role = "assistant", calls = result.calls, rawOutput = result.raw, model = p.model) +
                Message(sessionId = "s", role = "tool", toolCallId = "call1", toolName = "list_files", text = "README.md")
            assertEquals("文件已检查。", api.stream(p, "s", history, Tools.schemas()) {}.text)
            val requests = (0..1).map { requireNotNull(server.takeRequest()) }
            requests.forEach { request ->
                assertEquals("/responses", request.path)
                val body = JSONObject(request.body.readUtf8())
                assertEquals("auto", body.getString("tool_choice"))
                assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
                assertEquals(32768, body.getInt("max_output_tokens"))
            }
            val replay = ProtocolCodec.request(p, "s", history, Tools.schemas()).getJSONArray("input").objects()
            assertEquals("Inspect files first.", replay.first { it.optString("type") == "reasoning" }.getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals("call1", replay.first { it.optString("type") == "function_call_output" }.getString("call_id"))
            assertEquals(2, server.requestCount)
        }
    }
}
