package app.chocola.ui

import app.chocola.data.Message
import app.chocola.data.ToolCall
import org.json.JSONObject

internal data class ToolSection(val label: String, val text: String) {
    val chunks = displayLines(text).chunked(12).map { it.joinToString("\n") }
}
internal data class ToolDocument(val command: ToolSection, val result: ToolSection, val parameters: ToolSection) {
    companion object {
        fun prepare(message: Message?, call: ToolCall?) = ToolDocument(
            ToolSection("命令", operationCommand(call)),
            ToolSection("结果", message?.let { operationOutput(it).ifBlank { it.error } }.orEmpty()),
            ToolSection("参数", call?.let { runCatching { it.executionArguments().toString(2) }.getOrDefault(it.arguments) }.orEmpty()))
    }
}
