package app.chocola.agent

import app.chocola.api.*
import app.chocola.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class McpManager(private val repository: Repository) {
    private data class Peer(val connection: McpConnection, val tools: List<JSONObject>)
    private val peers = ConcurrentHashMap<String, Peer>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun key(session: String, server: McpServer) = "${SessionIds.runtimeKey(session)}:${server.id}:${server.fingerprint}"
    private suspend fun connection(server: McpServer, session: String): McpConnection {
        server.validate()
        val wire = if (server.transport == "http") McpHttpTransport(server, logs = repository.logs, session = session) else {
            val process = repository.runtime.openMcp(server.command, server.arguments, repository.workspace(session), server.environment)
            McpStdioTransport(process) { line -> repository.logs.event("mcp.stderr", mapOf("server" to server.id, "line" to Redactor.clean(line))) }
        }
        return McpConnection(wire).also { try { it.initialize() } catch (e: Exception) { it.close(); throw e } }
    }
    suspend fun check(server: McpServer): McpServer = withContext(Dispatchers.IO) {
        val session = "mcp-check-${server.id.replace(Regex("[^A-Za-z0-9_-]"), "").take(32)}-${newId().take(8)}"
        Redactor.register(server.token); server.headers.values.forEach(Redactor::register); server.environment.values.forEach(Redactor::register)
        repository.phase(session, "正在检查 MCP")
        try {
            TaskService.start(repository.context)
            withTimeout(if (server.transport == "stdio") 360_000 else 90_000) {
                connection(server, session).use { connected ->
                    val tools = connected.tools()
                    server.copy(tools = jsonArray(tools).toString(), status = "可用", protocolVersion = connected.version).also { McpNames.register(it) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (server.transport == "stdio") repository.runtime.dispose(session)
                repository.phase(session); TaskService.stopIfIdle(repository.context)
                File(repository.context.filesDir, "workspaces/$session").deleteRecursively()
            }
        }
    }
    fun schemas(session: Session): List<JSONObject> {
        if (session.access in setOf(Access.CHAT, Access.PLAN)) return emptyList()
        return repository.state.value.mcpServers.filter { it.enabled }.flatMap { server ->
            McpNames.register(server)
            JSONArray(server.tools).objects().filter { it.getString("name") !in server.disabledTools }.map { tool ->
                val parameters = JSONObject(tool.getJSONObject("inputSchema").toString())
                val properties = parameters.optJSONObject("properties") ?: JSONObject().also { parameters.put("properties", it) }
                properties.put(McpNames.descriptionKey(tool), JSONObject().put("type", "string")
                    .put("description", "用一句简短中文说明这一步的具体目的，不复述工具名或参数。此字段由客户端展示，不发送给服务。"))
                JSONObject().put("name", McpNames.alias(server, tool)).put("description", "${server.name}: ${tool.optString("description").ifBlank { tool.optString("title", tool.getString("name")) }}")
                    .put("strict", false).put("parameters", parameters)
            }
        }
    }
    /** Discovery never starts stdio processes, sends HTTP, or runs a remote tool. */
    fun discover(session: Session, query: String): JSONObject {
        require(query.isNotBlank()) { "请说明当前任务需要的能力" }
        val words = query.lowercase().split(Regex("[\\s,，/;；]+" )).filter { it.length > 1 }
        val all = schemas(session)
        val ranked = all.map { tool ->
            val text = (tool.getString("name") + " " + tool.optString("description")).lowercase()
            tool to words.sumOf { word -> if (text.contains(word)) word.length else 0 }
        }.sortedByDescending { it.second }
        val selected = ranked.filter { it.second > 0 }.ifEmpty { if (ranked.size <= 8) ranked else emptyList() }.take(8).map { it.first }
        repository.logs.event("mcp.discovered", mapOf("session" to session.id, "matches" to selected.map { it.getString("name") }, "catalogSize" to all.size))
        return JSONObject().put("tools", jsonArray(selected.map { JSONObject().put("name", it.getString("name")).put("description", it.optString("description")) }))
            .put("servers", jsonArray(repository.state.value.mcpServers.filter { it.enabled }.map { server -> JSONObject().put("name", server.name).put("tools", jsonArray(JSONArray(server.tools).objects().filter { it.getString("name") !in server.disabledTools }.map { it.getString("name") })) }))
            .put("more_available", all.size > selected.size).put("instruction", "These tools are now callable. Use only those relevant to the user's request; discovery is not a request to execute.")
    }
    fun describe(call: ToolCall): ToolCall {
        if (!McpNames.isTool(call.name)) return call
        repository.state.value.mcpServers.forEach { server ->
            JSONArray(server.tools).objects().firstOrNull { McpNames.alias(server, it) == call.name }?.let { tool ->
                return call.copy(displayName = McpNames.label(call.name), remoteName = tool.getString("name"), descriptionKey = McpNames.descriptionKey(tool))
            }
        }
        return call
    }
    suspend fun execute(session: Session, call: ToolCall): String = withContext(Dispatchers.IO) {
        val pair = repository.state.value.mcpServers.filter { it.enabled }.firstNotNullOfOrNull { server ->
            JSONArray(server.tools).objects().firstOrNull { it.getString("name") !in server.disabledTools && McpNames.alias(server, it) == call.name }?.let { server to it }
        } ?: error("MCP 工具已停用或配置已变更，请重新检查服务并授权")
        val (server, tool) = pair
        require(session.access !in setOf(Access.CHAT, Access.PLAN)) { "当前模式不允许 MCP 操作" }
        val key = key(session.id, server)
        val peer = locks.getOrPut(key) { Mutex() }.withLock {
            peers[key] ?: connection(server, session.id).let { connected ->
                try { Peer(connected, connected.tools()).also { peers[key] = it } }
                catch (e: Exception) { connected.close(); throw e }
            }
        }
        val live = peer.tools.firstOrNull { McpNames.alias(server, it) == call.name }
        require(live != null) { "MCP 工具定义已变更，请在设置中重新检查并授权" }
        try {
            val result = locks.getOrPut(key) { Mutex() }.withLock { peer.connection.call(live, call.executionArguments()) }
            // Large binary content becomes a workspace artifact, instead of flooding model context.
            fun normalize(node: Any?) {
                if (node is JSONArray) node.objects().forEach(::normalize)
                if (node !is JSONObject) return
                val blobKey = when { node.optString("type") in setOf("image", "audio") && node.has("data") -> "data"; node.has("blob") && node.has("mimeType") -> "blob"; else -> "" }
                if (blobKey.isNotBlank()) {
                    val encoded = node.getString(blobKey); require(encoded.length <= 10_000_000) { "MCP 二进制结果过大" }
                    val bytes = Base64.getDecoder().decode(encoded)
                    val relative = ".chocola/mcp/${newId()}.bin"
                    WorkspacePaths(repository.workspace(session.id)).resolve(relative).apply { parentFile?.mkdirs(); writeBytes(bytes) }
                    node.remove(blobKey); node.put("saved_path", "/workspace/$relative").put("bytes", bytes.size)
                }
                node.keys().asSequence().toList().forEach { normalize(node.opt(it)) }
            }
            normalize(result)
            if (result.optBoolean("isError")) error(Redactor.clean(result.optJSONArray("content")?.objects()?.joinToString("\n") { it.optString("text") }.orEmpty()).ifBlank { "MCP 工具执行失败" })
            Redactor.clean(result.toString())
        } catch (e: Exception) {
            peers.remove(key)?.connection?.close()
            throw e
        }
    }
    fun closeSession(session: String) { peers.keys.filter { it.startsWith("${SessionIds.runtimeKey(session)}:") }.forEach { key -> peers.remove(key)?.connection?.close(); locks.remove(key) } }
    fun closeServer(id: String) { peers.keys.filter { it.substringAfter(':').substringBefore(':') == id }.forEach { key -> peers.remove(key)?.connection?.close(); locks.remove(key) } }
    fun closeAll() { peers.values.forEach { it.connection.close() }; peers.clear(); locks.clear() }
}
