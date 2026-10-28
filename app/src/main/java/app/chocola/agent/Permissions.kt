package app.chocola.agent

import app.chocola.data.*
import java.io.File
import org.json.JSONObject

enum class PermissionDecision { ALLOW, ASK, DENY }

object PermissionPolicy {
    val scheduleWrites = setOf("save_schedule", "delete_schedule")
    val scheduleTools = scheduleWrites + "list_schedules"
    val knownTools = setOf("list_files", "read_file", "search_files", "write_file", "edit_file", "run_shell", "start_process", "read_process", "stop_process", "http_request", "ssh_execute", "recall_memory", "remember", "task_plan", "load_skill", "generate_image") + scheduleTools + NotebookTools.names + "discover_tools"
    val conversationTools = scheduleTools + setOf("load_skill", "generate_image") + NotebookTools.names
    val reads = setOf("list_files", "read_file", "search_files", "read_process", "recall_memory", "task_plan", "list_schedules", "load_skill", "discover_tools", "list_notebooks", "read_notebook")
    fun known(tool: String) = tool in knownTools || McpNames.isTool(tool)
    fun decision(access: Access, tool: String, autoMemory: Boolean = false, rule: String = "", arguments: String = "{}"): PermissionDecision {
        if (!known(tool) || (access == Access.CHAT && tool !in conversationTools) || rule == "deny") return PermissionDecision.DENY
        if (access == Access.PLAN && tool !in reads) {
            val method = runCatching { JSONObject(arguments).optString("method", "GET").uppercase() }.getOrDefault("")
            if (tool != "http_request" || method !in setOf("GET", "HEAD")) return PermissionDecision.DENY
        }
        if (rule == "ask") return PermissionDecision.ASK
        if (tool == "generate_image" && access != Access.PLAN) return PermissionDecision.ALLOW
        if (tool in scheduleTools && access != Access.PLAN) return PermissionDecision.ALLOW
        if (tool in NotebookTools.writes && access in setOf(Access.CHAT, Access.WORKSPACE)) return PermissionDecision.ALLOW
        if (rule == "allow" || access == Access.FULL || tool in reads || tool == "stop_process") return PermissionDecision.ALLOW
        if (tool == "remember" && autoMemory && access != Access.PLAN) return PermissionDecision.ALLOW
        if (access == Access.WORKSPACE && tool in setOf("write_file", "edit_file")) return PermissionDecision.ALLOW
        return PermissionDecision.ASK
    }
    fun scheduled(session: Session, call: ToolCall, preferences: Preferences): Boolean {
        if (!known(call.name) || call.name in scheduleWrites || call.name == "start_process") return false
        // These grants are reviewed for this task only; other conversations retain their rules.
        if (call.name in session.taskTools) return true
        return decision(session.access, call.name, preferences.autoMemory, preferences.toolRules[call.name].orEmpty(), call.arguments) == PermissionDecision.ALLOW
    }
    fun allowed(access: Access, tool: String, autoMemory: Boolean = false): Boolean {
        return decision(access, tool, autoMemory) == PermissionDecision.ALLOW
    }
    fun available(access: Access, tool: String) = (access != Access.CHAT || tool in conversationTools) && (access != Access.PLAN || tool in reads || tool == "http_request")
}
class WorkspacePaths(root: File) {
    val root: File = root.canonicalFile
    fun resolve(path: String): File {
        val relative = when {
            path == "/workspace" -> ""
            path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
            path.startsWith('/') -> throw IllegalArgumentException("路径必须位于 /workspace")
            else -> path
        }
        require(!relative.contains('\u0000')) { "无效路径" }
        val file = File(root, relative).canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator)) { "路径超出当前工作区" }
        return file
    }
    fun guest(file: File): String = "/workspace/" + file.relativeTo(root).invariantSeparatorsPath
}
