package app.chocola.agent

import app.chocola.data.*
import kotlinx.coroutines.withTimeout

internal class SchedulePreflight(private val repository: Repository) {
    suspend fun inspect(task: ScheduledTask): ScheduleRequirements = withTimeout(60_000) {
        val state = repository.state.value
        val provider = state.providers.firstOrNull { it.id == task.providerId } ?: error("请选择模型")
        val memories = state.memories.filter { it.enabled }
        val context = state.messages[task.sessionId].orEmpty().filter { it.role in setOf("user", "assistant") }
            .takeLast(6).joinToString("\n") { "${it.role}: ${Redactor.clean(it.text.take(900))}" }
        val recentCalls = state.messages[task.sessionId].orEmpty().flatMap { it.calls }.takeLast(8).map { call ->
            val args = runCatching { org.json.JSONObject(call.arguments) }.getOrDefault(org.json.JSONObject())
            org.json.JSONObject().put("tool", call.name).put("credential_id", args.optString("credential_id")).put("description", Redactor.clean(args.optString("description")))
        }
        val mcpTools = repository.mcp.schemas(Session(access = Access.ASK))
        val availableTools = PermissionPolicy.knownTools + mcpTools.map { it.getString("name") }
        val inventory = jsonArray(memories.map(Tools::visibleMemory))
        val result = repository.engine.api.stream(ModelCatalog.auxiliary(provider).copy(maxTokens = 4096),
            "检查定时任务执行需要的工具和凭据，只规划检查，不执行任务。返回严格JSON：{\"tools\":[工具名],\"credential_ids\":[资料id],\"needs_credential\":是否需要资料,\"credential_kind\":资料类型或空字符串,\"issues\":[其它无法执行的具体条件]}。可用工具：${(availableTools - PermissionPolicy.scheduleWrites - "start_process").sorted().joinToString(",")}。服务器检查使用ssh_execute与对应服务器凭据，必要时可用run_shell；GitHub请求使用http_request和GitHub凭据。选择明确对应任务的资料，不能编造凭据，不能把资料尚未开启tools_allowed当作缺失（用户将在任务授权页单独授权）。已选资料优先，避免重复要求用户补充已有资料。若缺少资料或有多台服务器且无法确定目标，只用needs_credential=true和credential_kind=服务器表达，并将credential_ids留空，让用户在授权页选择，不要把此类问题写进issues。issues只放无法通过选择资料解决的任务条件。不要返回密钥、命令、执行结果或其它文字。",
            listOf(Message(sessionId = task.sessionId, role = "user", text = "任务：${task.prompt}\n已选资料ID：${task.credentialIds.joinToString(",")}\n可用资料：$inventory\n来源对话：$context\n近期操作引用：${jsonArray(recentCalls)}\nMCP 工具目录（使用这里的准确工具名）：${jsonArray(mcpTools)}\nSkill 目录：${repository.skillStore.manifest()}"))) {}
        ScheduleRequirements.from(result.text, memories, availableTools)
    }
}
