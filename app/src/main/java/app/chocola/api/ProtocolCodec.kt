package app.chocola.api

import app.chocola.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Base64

data class ApiResult(val text: String, val calls: List<ToolCall> = emptyList(), val raw: String = "", val inputTokens: Int = 0, val outputTokens: Int = 0, val stopReason: String = "", val attachments: List<Attachment> = emptyList(), val reasoningTokens: Int? = null)
class ApiOutputFailure(message: String, val partial: ApiResult) : IllegalStateException(message)
class ModelIncomplete(val reason: String, val partial: ApiResult) : IllegalStateException(when (reason) {
    "max_output_tokens", "max_tokens", "length" -> if (partial.text.isBlank()) "本次输出预算已用尽，未收到完整回复" else "已达到本次输出上限，收到的正文已保留"
    "content_filter" -> "供应商未完成此次输出，请调整请求后再试"
    else -> "供应商未完成此次输出：$reason"
})

object ProtocolCodec {
    private fun usesOpenAIToolAllowlist(p: Provider): Boolean {
        val host = runCatching { URI(p.baseUrl.trim()).host?.lowercase().orEmpty() }.getOrDefault("")
        // An OpenAI model-family override does not change the official provider's wire format.
        if (host == "api.deepseek.com" || host.endsWith(".deepseek.com") ||
            host == "open.bigmodel.cn" || host == "api.z.ai") return false
        // Compatible Responses APIs do not necessarily implement OpenAI's allowed_tools object.
        // Preserve the verified OpenAI/Codex proxy restriction; other providers use auto/none.
        return ModelCatalog.family(p) == "openai"
    }
    fun endpoint(p: Provider, models: Boolean = false): String {
        val base = p.baseUrl.trim().trimEnd('/')
        val known = listOf("/chat/completions", "/responses", "/messages")
        val stripped = (known.firstOrNull { base.endsWith(it) }?.let { base.removeSuffix(it) } ?: base).let {
            if (p.protocol == Protocol.ANTHROPIC && (it.endsWith("/anthropic") || it == "https://api.anthropic.com")) "$it/v1" else it
        }.let {
            // BigModel's Responses and Chat APIs use different documented base paths.
            // Limit this correction to its two standard endpoints; never reroute a Coding Plan.
            when {
                p.protocol == Protocol.RESPONSES && it == "https://open.bigmodel.cn/api/paas/v4" -> "https://open.bigmodel.cn/api/v1"
                p.protocol == Protocol.CHAT && it == "https://open.bigmodel.cn/api/v1" -> "https://open.bigmodel.cn/api/paas/v4"
                else -> it
            }
        }
        if (models) return "$stripped/models"
        return stripped + when (p.protocol) { Protocol.CHAT -> "/chat/completions"; Protocol.RESPONSES -> "/responses"; Protocol.ANTHROPIC -> "/messages" }
    }
    private fun imageUrl(a: Attachment): String {
        require(a.size <= 20_000_000 && File(a.path).length() <= 20_000_000) { "图片不能超过 20 MB" }
        return "data:${a.mime};base64," + Base64.getEncoder().encodeToString(File(a.path).readBytes())
    }
    private fun chatContent(m: Message): Any {
        if (m.attachments.none { it.mime.startsWith("image/") }) return AttachmentInputs.text(m)
        return JSONArray().put(JSONObject().put("type", "text").put("text", AttachmentInputs.text(m))).also { a ->
            m.attachments.filter { it.mime.startsWith("image/") }.forEach { a.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageUrl(it)))) }
        }
    }
    fun request(p: Provider, system: String, messages: List<Message>, tools: JSONArray = JSONArray()): JSONObject {
        val body = JSONObject().put("model", p.model).put("stream", true)
        ModelCatalog.parameters(p).let { params -> params.keys().forEach { body.put(it, params.get(it)) } }
        when (p.protocol) {
            Protocol.CHAT -> {
                val array = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                messages.forEach { m ->
                    val item = JSONObject().put("role", m.role)
                    when (m.role) {
                        "tool" -> item.put("tool_call_id", m.toolCallId).put("content", m.text)
                        "assistant" -> {
                            if ((m.model.isBlank() || m.model == p.model) && m.rawOutput.startsWith('{')) {
                                val raw = JSONObject(m.rawOutput)
                                raw.keys().forEach { item.put(it, raw.get(it)) }
                            }
                            item.put("content", AttachmentInputs.text(m).ifBlank { null } ?: JSONObject.NULL)
                            if (m.calls.isNotEmpty() && !item.has("tool_calls")) item.put("tool_calls", jsonArray(m.calls.map { call ->
                                JSONObject().put("id", call.id).put("type", "function").put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
                            }))
                            if (ModelCatalog.family(p) == "deepseek" && tools.length() > 0 && item.isNull("reasoning_content")) item.put("reasoning_content", "")
                        }
                        else -> item.put("content", chatContent(m))
                    }
                    array.put(item)
                }
                body.put("messages", array)
                if (p.tokenParameter in setOf("max_tokens", "max_completion_tokens")) body.put(p.tokenParameter, p.maxTokens)
                if (tools.length() > 0) body.put("tools", jsonArray(tools.objects().map { JSONObject().put("type", "function").put("function", it) }))
            }
            Protocol.ANTHROPIC -> {
                val array = JSONArray()
                messages.forEach { m ->
                    val content = JSONArray()
                    when (m.role) {
                        "tool" -> content.put(JSONObject().put("type", "tool_result").put("tool_use_id", m.toolCallId).put("content", m.text).put("is_error", m.status == "error"))
                        "assistant" -> {
                            val raw = if ((m.model.isBlank() || m.model == p.model) && m.rawOutput.startsWith('[')) runCatching { JSONArray(m.rawOutput).objects() }.getOrNull() else null
                            if (raw != null && raw.all { it.optString("type") in setOf("text", "thinking", "redacted_thinking", "tool_use") }) raw.forEach(content::put)
                            else {
                                if (m.text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", m.text))
                                m.calls.forEach { content.put(JSONObject().put("type", "tool_use").put("id", it.id).put("name", it.name).put("input", JSONObject(it.arguments))) }
                            }
                            val images = m.attachments.filter { it.mime.startsWith("image/") }
                            if (images.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", images.joinToString("\n", transform = AttachmentInputs::reference)))
                        }
                        else -> {
                            val text = AttachmentInputs.text(m)
                            if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
                            m.attachments.filter { it.mime.startsWith("image/") }.forEach {
                                content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64")
                                    .put("media_type", it.mime).put("data", imageUrl(it).substringAfter(','))))
                            }
                        }
                    }
                    if (content.length() > 0) {
                        val role = if (m.role == "assistant") "assistant" else "user"
                        val previous = array.optJSONObject(array.length() - 1)
                        if (previous?.optString("role") == role) content.objects().forEach { previous.getJSONArray("content").put(it) }
                        else array.put(JSONObject().put("role", role).put("content", content))
                    }
                }
                body.put("system", system).put("messages", array).put("max_tokens", p.maxTokens)
                if (tools.length() > 0) body.put("tools", jsonArray(tools.objects().map { JSONObject().put("name", it.getString("name"))
                    .put("description", it.getString("description")).put("input_schema", it.getJSONObject("parameters")) }))
            }
            Protocol.RESPONSES -> {
                val array = JSONArray()
                messages.forEach { m ->
                    when (m.role) {
                        "tool" -> array.put(JSONObject().put("type", "function_call_output").put("call_id", m.toolCallId).put("output", m.text))
                        "assistant" -> {
                            val raw = if ((m.model.isBlank() || m.model == p.model) && m.rawOutput.startsWith('[')) JSONArray(m.rawOutput).objects()
                                .filter { it.optString("type") in setOf("message", "reasoning", "function_call", "compaction") } else emptyList()
                            if (raw.isNotEmpty()) {
                                raw.forEach { array.put(it) }
                                val images = m.attachments.filter { it.mime.startsWith("image/") }
                                if (images.isNotEmpty()) array.put(JSONObject().put("role", "assistant").put("content", images.joinToString("\n", transform = AttachmentInputs::reference)))
                            }
                            else {
                                val text = AttachmentInputs.text(m)
                                if (text.isNotBlank()) array.put(JSONObject().put("role", "assistant").put("content", text))
                                m.calls.forEach { array.put(JSONObject().put("type", "function_call").put("call_id", it.id).put("name", it.name).put("arguments", it.arguments)) }
                            }
                        }
                        else -> {
                            val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", AttachmentInputs.text(m)))
                            m.attachments.filter { it.mime.startsWith("image/") }.forEach { content.put(JSONObject().put("type", "input_image").put("image_url", imageUrl(it))) }
                            array.put(JSONObject().put("role", "user").put("content", content))
                        }
                    }
                }
                body.put("instructions", system).put("input", array).put("max_output_tokens", p.maxTokens).put("store", false)
                body.put("include", JSONArray().put("reasoning.encrypted_content"))
                if (tools.length() > 0) {
                    body.put("tools", jsonArray(tools.objects().map { JSONObject(it.toString()).put("type", "function") }))
                    // OpenAI/Codex proxies may inject native image/web tools. Use their allowlist
                    // only for that adapter; DeepSeek, GLM and other compatible APIs use auto/none.
                    if (body.optString("tool_choice") != "none") {
                        if (usesOpenAIToolAllowlist(p)) body.put("tool_choice", JSONObject().put("type", "allowed_tools").put("mode", "auto")
                            .put("tools", jsonArray(tools.objects().map { JSONObject().put("type", "function").put("name", it.getString("name")) })))
                        else body.put("tool_choice", "auto")
                    }
                } else body.put("tool_choice", "none")
            }
        }
        return body
    }
    fun decodeJson(p: Protocol, j: JSONObject, allowIncomplete: Boolean = false): ApiResult {
        if (p == Protocol.RESPONSES && j.optString("status") == "failed") throw IllegalStateException(j.optJSONObject("error")?.optString("message") ?: "供应商请求失败")
        val result = when (p) {
        Protocol.CHAT -> {
            val m = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val usage = j.optJSONObject("usage")
            ApiResult(m.optString("content").takeUnless { it == "null" }.orEmpty(), m.optJSONArray("tool_calls")?.objects()?.map {
                ToolCall(it.getString("id"), it.getJSONObject("function").getString("name"), it.getJSONObject("function").getString("arguments"))
            } ?: emptyList(), raw = m.toString(), inputTokens = usage?.optInt("prompt_tokens") ?: 0, outputTokens = usage?.optInt("completion_tokens") ?: 0,
                stopReason = j.getJSONArray("choices").getJSONObject(0).optString("finish_reason").takeIf { it != "null" }.orEmpty())
        }
        Protocol.ANTHROPIC -> {
            val blocks = j.getJSONArray("content").objects()
            ApiResult(blocks.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") },
                blocks.filter { it.optString("type") == "tool_use" }.map { ToolCall(it.getString("id"), it.getString("name"), it.getJSONObject("input").toString()) },
                raw = j.getJSONArray("content").toString(), inputTokens = j.optJSONObject("usage")?.optInt("input_tokens") ?: 0, outputTokens = j.optJSONObject("usage")?.optInt("output_tokens") ?: 0,
                stopReason = j.optString("stop_reason"))
        }
        Protocol.RESPONSES -> {
            val output = j.optJSONArray("output") ?: JSONArray()
            ApiResult(output.objects().filter { it.optString("type") == "message" }.flatMap {
                it.optJSONArray("content")?.objects() ?: listOfNotNull(it.optJSONObject("content")) }
                .filter { it.optString("type") == "output_text" }.joinToString("") { it.optString("text") },
                output.objects().filter { it.optString("type") == "function_call" }.map { ToolCall(it.getString("call_id"), it.getString("name"), it.getString("arguments")) },
                output.toString(), j.optJSONObject("usage")?.optInt("input_tokens") ?: 0, j.optJSONObject("usage")?.optInt("output_tokens") ?: 0,
                if (j.optString("status") == "incomplete") j.optJSONObject("incomplete_details")?.optString("reason", "unknown") ?: "unknown" else "")
        }
        }.let { result ->
            val usage = j.optJSONObject("usage")
            val details = usage?.optJSONObject(if (p == Protocol.CHAT) "completion_tokens_details" else "output_tokens_details")
            result.copy(reasoningTokens = details?.takeIf { !it.isNull("reasoning_tokens") }?.optInt("reasoning_tokens"))
        }
        if (!allowIncomplete && ((p == Protocol.RESPONSES && j.optString("status") == "incomplete") || result.stopReason in setOf("length", "max_tokens", "max_output_tokens", "content_filter", "unknown"))) throw ModelIncomplete(result.stopReason, result)
        return result
    }
}

/** Protocol event assembler; no Android dependency, exercised with real SSE fixtures. */
class StreamAccumulator(private val protocol: Protocol) {
    private data class CallParts(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), val extras: JSONObject = JSONObject())
    private val calls = sortedMapOf<Int, CallParts>()
    private val text = StringBuilder()
    private var raw = ""
    private var input = 0
    private var output = 0
    private var reasoningTokens: Int? = null
    private var stopReason = ""
    private var finished = false
    private var incomplete = false
    private val reasoning = StringBuilder()
    private val messageExtras = JSONObject()
    private val blocks = sortedMapOf<Int, JSONObject>()
    private val responseItems = sortedMapOf<Int, JSONObject>()
    private val blockDeltas = mutableMapOf<Pair<Int, String>, StringBuilder>()
    var complete = false
        private set
    fun accept(event: String, data: String) {
        if (data == "[DONE]") { complete = true; return }
        if (data.isBlank()) return
        accept(event, JSONObject(data))
    }
    fun accept(event: String, j: JSONObject) {
        if (event == "error" || j.has("error")) throw IllegalStateException(j.optJSONObject("error")?.optString("message") ?: "API 流返回错误")
        when (protocol) {
            Protocol.CHAT -> {
                j.optJSONObject("usage")?.let {
                    input = it.optInt("prompt_tokens"); output = it.optInt("completion_tokens")
                    it.optJSONObject("completion_tokens_details")?.takeIf { details -> !details.isNull("reasoning_tokens") }?.let { details -> reasoningTokens = details.optInt("reasoning_tokens") }
                }
                j.optJSONArray("choices")?.objects()?.forEach { choice ->
                    if (!choice.isNull("finish_reason") && choice.optString("finish_reason").isNotBlank()) { finished = true; stopReason = choice.getString("finish_reason") }
                    choice.optJSONObject("delta")?.let { d ->
                        if (!d.isNull("content")) text.append(d.optString("content"))
                        if (!d.isNull("reasoning_content")) reasoning.append(d.optString("reasoning_content"))
                        d.keys().forEach { if (it !in setOf("content", "reasoning_content", "tool_calls", "role")) messageExtras.put(it, d.get(it)) }
                        d.optJSONArray("tool_calls")?.objects()?.forEach { t ->
                            val parts = calls.getOrPut(t.optInt("index")) { CallParts() }
                            t.keys().forEach { if (it !in setOf("index", "id", "function")) parts.extras.put(it, t.get(it)) }
                            if (t.optString("id").isNotEmpty()) parts.id = t.getString("id")
                            t.optJSONObject("function")?.let { f ->
                                if (f.optString("name").isNotEmpty()) parts.name += f.getString("name")
                                if (!f.isNull("arguments")) parts.args.append(f.optString("arguments"))
                            }
                        }
                    }
                }
            }
            Protocol.ANTHROPIC -> when (event.ifBlank { j.optString("type") }) {
                "message_start" -> input = j.optJSONObject("message")?.optJSONObject("usage")?.optInt("input_tokens") ?: 0
                "content_block_start" -> j.optJSONObject("content_block")?.let { b ->
                    blocks[j.optInt("index")] = b
                    if (b.optString("type") == "tool_use") calls[j.optInt("index")] = CallParts(b.getString("id"), b.getString("name"))
                    if (b.optString("type") == "text") text.append(b.optString("text"))
                }
                "content_block_delta" -> j.getJSONObject("delta").let { d ->
                    when (d.optString("type")) {
                        "text_delta" -> text.append(d.optString("text"))
                        "input_json_delta" -> calls[j.optInt("index")]?.args?.append(d.optString("partial_json"))
                    }
                    val field = when (d.optString("type")) { "text_delta" -> "text"; "thinking_delta" -> "thinking"; "signature_delta" -> "signature"; else -> null }
                    if (field != null) blockDeltas.getOrPut(j.optInt("index") to field) { StringBuilder(blocks[j.optInt("index")]?.optString(field).orEmpty()) }.append(d.optString(field))
                }
                "message_delta" -> { output = j.optJSONObject("usage")?.optInt("output_tokens") ?: output; stopReason = j.optJSONObject("delta")?.optString("stop_reason").orEmpty() }
                "message_stop" -> complete = true
            }
            Protocol.RESPONSES -> when (j.optString("type", event)) {
                "response.output_text.delta" -> text.append(j.optString("delta"))
                "response.output_item.added" -> j.optJSONObject("item")?.let { item ->
                    if (item.optString("type") == "function_call") calls[j.optInt("output_index")] = CallParts(item.optString("call_id"), item.optString("name"))
                }
                "response.output_item.done" -> j.optJSONObject("item")?.let { item ->
                    responseItems[j.optInt("output_index")] = item
                }
                "response.function_call_arguments.delta" -> calls.getOrPut(j.optInt("output_index")) { CallParts() }.args.append(j.optString("delta"))
                "response.completed", "response.done" -> {
                    val response = j.getJSONObject("response")
                    if ((response.optJSONArray("output")?.length() ?: 0) == 0 && responseItems.isNotEmpty()) response.put("output", jsonArray(responseItems.values))
                    val result = ProtocolCodec.decodeJson(Protocol.RESPONSES, response)
                    text.clear(); text.append(result.text); raw = result.raw; input = result.inputTokens; output = result.outputTokens
                    reasoningTokens = result.reasoningTokens
                    calls.clear(); result.calls.forEachIndexed { index, call -> calls[index] = CallParts(call.id, call.name, StringBuilder(call.arguments)) }
                    complete = true
                }
                "response.incomplete" -> {
                    val result = ProtocolCodec.decodeJson(Protocol.RESPONSES, j.getJSONObject("response"), allowIncomplete = true)
                    if (result.text.isNotBlank()) { text.clear(); text.append(result.text) }
                    raw = result.raw; input = result.inputTokens; output = result.outputTokens
                    reasoningTokens = result.reasoningTokens
                    stopReason = result.stopReason.ifBlank { "unknown" }; incomplete = true; complete = true
                }
                "response.failed" -> throw IllegalStateException(j.optJSONObject("response")?.optJSONObject("error")?.optString("message") ?: "供应商请求失败")
            }
        }
        require(text.length < 8_000_000 && reasoning.length < 8_000_000 && calls.values.sumOf { it.args.length } < 2_000_000) { "API 输出超过限制" }
    }
    fun text() = text.toString()
    fun result(): ApiResult {
        check(complete || finished) { "连接提前结束，已保留收到的内容" }
        if (incomplete || stopReason in setOf("length", "max_tokens", "max_output_tokens", "content_filter", "unknown")) throw ModelIncomplete(stopReason, ApiResult(text(), raw = raw, inputTokens = input, outputTokens = output, stopReason = stopReason, reasoningTokens = reasoningTokens))
        val assembled = calls.values.map { c ->
            require(c.id.isNotBlank() && c.name.isNotBlank()) { "API 返回了不完整的工具调用" }
            val args = c.args.toString().ifBlank { "{}" }
            JSONObject(args)
            ToolCall(c.id, c.name, args)
        }
        when (protocol) {
            Protocol.CHAT -> {
                val message = JSONObject(messageExtras.toString()).put("role", "assistant").put("content", text().ifBlank { null } ?: JSONObject.NULL)
                if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
                if (assembled.isNotEmpty()) message.put("tool_calls", jsonArray(calls.values.zip(assembled).map { (parts, call) ->
                    JSONObject(parts.extras.toString()).put("id", call.id).put("type", "function").put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
                }))
                raw = message.toString()
            }
            Protocol.ANTHROPIC -> {
                blockDeltas.forEach { (key, value) -> blocks[key.first]?.put(key.second, value.toString()) }
                blocks.forEach { (index, block) -> if (block.optString("type") == "tool_use") block.put("input", JSONObject(calls[index]?.args?.toString().orEmpty().ifBlank { "{}" })) }
                raw = jsonArray(blocks.values).toString()
            }
            Protocol.RESPONSES -> if (raw.isBlank() && responseItems.isNotEmpty()) raw = jsonArray(responseItems.values).toString()
        }
        return ApiResult(text(), assembled, raw, input, output, stopReason, reasoningTokens = reasoningTokens)
    }
}
