package app.chocola.api

import app.chocola.data.Protocol
import app.chocola.data.objects
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class ReplyPhase(val label: String) {
    WAITING("正在等待模型"), THINKING("正在思考"), TOOL("正在准备操作"), TEXT("正在回复"), IMAGE("正在绘图")
}

/** Only event names and lengths leave this tracker. Reasoning and tool arguments are never logged. */
internal class StreamActivity(private val protocol: Protocol) {
    @Volatile var phase = ReplyPhase.WAITING
        private set
    @Volatile private var textChars = 0L
    @Volatile private var reasoningChars = 0L
    @Volatile private var argumentChars = 0L
    @Volatile private var unknownEvents = 0L
    @Volatile private var lastEvent = ""
    private val types = ConcurrentHashMap<String, AtomicLong>()

    private fun text(value: String) { if (value.isNotEmpty()) { textChars += value.length; phase = ReplyPhase.TEXT } }
    private fun thinking(value: String) { if (value.isNotEmpty()) { reasoningChars += value.length; phase = ReplyPhase.THINKING } }
    private fun arguments(value: String) { argumentChars += value.length; phase = ReplyPhase.TOOL }
    private fun record(type: String) {
        lastEvent = type
        types.getOrPut(type) { AtomicLong() }.incrementAndGet()
        if (type == "unknown") unknownEvents++
    }

    fun observe(event: String, j: JSONObject) {
        // A successful HTTP response with the wrong SSE schema otherwise looks like an endless wait.
        val responseEvent = j.optString("type", event)
        val actual = when {
            j.has("choices") -> Protocol.CHAT
            responseEvent.startsWith("response.") -> Protocol.RESPONSES
            responseEvent in setOf("message_start", "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop") -> Protocol.ANTHROPIC
            else -> null
        }
        check(actual == null || actual == protocol) { "接口返回的流格式与所选协议不一致，请检查模型的 API 协议和地址" }
        when (protocol) {
            Protocol.CHAT -> {
                record(if (j.has("choices")) "chat.completion.chunk" else if (j.has("usage")) "chat.usage" else "unknown")
                j.optJSONArray("choices")?.objects()?.forEach { choice ->
                    choice.optJSONObject("delta")?.let { delta ->
                        thinking(delta.optString("reasoning_content").takeUnless { it == "null" }.orEmpty())
                        text(delta.optString("content").takeUnless { it == "null" }.orEmpty())
                        delta.optJSONArray("tool_calls")?.objects()?.forEach { call ->
                            arguments(call.optJSONObject("function")?.optString("arguments").orEmpty().takeUnless { it == "null" }.orEmpty())
                        }
                    }
                }
            }
            Protocol.ANTHROPIC -> {
                record(responseEvent.takeIf { it in anthropicEvents } ?: "unknown")
                when (responseEvent) {
                    "content_block_start" -> when (j.optJSONObject("content_block")?.optString("type")) {
                        "thinking", "redacted_thinking" -> phase = ReplyPhase.THINKING
                        "tool_use" -> phase = ReplyPhase.TOOL
                        "text" -> text(j.optJSONObject("content_block")?.optString("text").orEmpty())
                    }
                    "content_block_delta" -> j.optJSONObject("delta")?.let { delta ->
                        when (delta.optString("type")) {
                            "thinking_delta" -> thinking(delta.optString("thinking"))
                            "text_delta" -> text(delta.optString("text"))
                            "input_json_delta" -> arguments(delta.optString("partial_json"))
                        }
                    }
                }
            }
            Protocol.RESPONSES -> {
                record(responseEvent.takeIf { it in responseEvents } ?: "unknown")
                when (responseEvent) {
                    "response.output_text.delta", "response.refusal.delta" -> text(j.optString("delta"))
                    "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> thinking(j.optString("delta"))
                    "response.function_call_arguments.delta", "response.custom_tool_call_input.delta" -> arguments(j.optString("delta"))
                    "response.output_item.added" -> when (j.optJSONObject("item")?.optString("type")) {
                        "reasoning" -> phase = ReplyPhase.THINKING
                        "function_call", "custom_tool_call" -> phase = ReplyPhase.TOOL
                        "image_generation_call" -> phase = ReplyPhase.IMAGE
                    }
                    "response.image_generation_call.in_progress", "response.image_generation_call.generating", "response.image_generation_call.partial_image" -> phase = ReplyPhase.IMAGE
                }
            }
        }
    }

    fun fields(): Map<String, Any?> = mapOf("streamPhase" to phase.name, "textDeltaChars" to textChars,
        "reasoningDeltaChars" to reasoningChars, "toolArgumentDeltaChars" to argumentChars,
        "unknownEvents" to unknownEvents, "lastEventType" to lastEvent,
        "eventTypes" to types.mapValues { it.value.get() })

    private companion object {
        val anthropicEvents = setOf("message_start", "message_delta", "message_stop", "content_block_start", "content_block_delta", "content_block_stop", "ping", "error")
        val responseEvents = setOf("response.created", "response.queued", "response.in_progress", "response.completed", "response.done", "response.failed", "response.incomplete",
            "response.output_item.added", "response.output_item.done", "response.content_part.added", "response.content_part.done",
            "response.output_text.delta", "response.output_text.done", "response.refusal.delta", "response.refusal.done",
            "response.reasoning_text.delta", "response.reasoning_text.done", "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
            "response.reasoning_summary_part.added", "response.reasoning_summary_part.done", "response.function_call_arguments.delta", "response.function_call_arguments.done",
            "response.custom_tool_call_input.delta", "response.custom_tool_call_input.done", "response.image_generation_call.in_progress",
            "response.image_generation_call.generating", "response.image_generation_call.partial_image", "response.image_generation_call.completed", "error")
    }
}
