package app.chocola.data

import app.chocola.agent.AgentEngine
import org.json.JSONObject

internal data class ChatActivity(val operation: String = "", val thinking: Boolean = false) {
    companion object {
        fun project(messages: List<Message>, running: Boolean, imageMode: Boolean = false): ChatActivity {
            if (!running) return ChatActivity()
            val visible = messages.filter { it.status != "replaced" }
            val tool = visible.lastOrNull { it.role == "tool" && it.status == "running" }
            if (tool != null) {
                val call = visible.asReversed().asSequence().flatMap { it.calls.asSequence() }.firstOrNull { it.id == tool.toolCallId }
                val description = call?.purpose().orEmpty()
                return ChatActivity(description.trim().take(80).ifBlank { call?.displayName?.ifBlank { null } ?: AgentEngine.toolLabel(tool.toolName) })
            }
            val last = visible.lastOrNull()
            if (last?.role == "assistant" && last.status == "streaming" && (imageMode || ImageModels.isImage(last.model))) return ChatActivity(operation = "正在生成图片")
            return ChatActivity(thinking = last != null && (last.role in setOf("user", "tool") ||
                (last.role == "assistant" && last.text.isBlank() && last.status == "streaming")))
        }
    }
}
