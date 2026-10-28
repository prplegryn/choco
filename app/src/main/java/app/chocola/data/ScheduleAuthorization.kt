package app.chocola.data

import app.chocola.agent.PermissionPolicy
import org.json.JSONObject

data class ScheduleRequirements(val tools: Set<String>, val credentials: Set<String>, val issues: List<String> = emptyList(),
    val needsCredential: Boolean = credentials.isNotEmpty() || "ssh_execute" in tools, val credentialKind: String = if ("ssh_execute" in tools) "服务器" else "") {
    companion object {
        fun from(text: String, memories: List<Memory>, availableTools: Set<String> = PermissionPolicy.knownTools): ScheduleRequirements {
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            require(start >= 0 && end > start) { "模型未返回有效的任务需求，请重试" }
            val j = JSONObject(text.substring(start, end + 1))
            val tools = j.optJSONArray("tools")?.strings()?.toSet().orEmpty()
            require(tools.all { it in availableTools && it !in PermissionPolicy.scheduleWrites && it != "start_process" }) { "任务需求包含不支持的工具，请调整任务内容" }
            val ids = j.optJSONArray("credential_ids")?.strings()?.toSet().orEmpty()
            require(ids.all { id -> memories.any { it.id == id && it.enabled } }) { "任务引用了不存在或停用的凭据" }
            return ScheduleRequirements(tools, ids, j.optJSONArray("issues")?.strings().orEmpty().filter(String::isNotBlank),
                j.optBoolean("needs_credential") || ids.isNotEmpty() || "ssh_execute" in tools,
                j.optString("credential_kind").ifBlank { if ("ssh_execute" in tools) "服务器" else "" })
        }
        fun missing(memory: Memory): List<String> {
            val fields = memory.fields.associate { it.name.lowercase() to it.value.trim() }
            return buildList {
                if (!memory.enabled) add("${memory.name} 已停用")
                if (memory.kind == "服务器") {
                    if (fields["host"].isNullOrBlank()) add("${memory.name} 缺少服务器地址")
                    if (fields["password"].isNullOrBlank() && fields["private_key"].isNullOrBlank()) add("${memory.name} 需要密码或私钥")
                } else if (memory.fields.any { it.secret } && memory.fields.none { it.secret && it.value.isNotBlank() }) add("${memory.name} 缺少凭据内容")
                if (memory.kind !in setOf("服务器", "GitHub") && memory.fields.any { it.secret } && memory.domain.isBlank()) add("${memory.name} 缺少允许访问的域名")
            }
        }
    }
}
data class ScheduleAuthorization(val id: String = newId(), val task: ScheduledTask, val requirements: ScheduleRequirements)
data class ScheduleConsent(val tools: Set<String>, val credentials: Set<String>)
