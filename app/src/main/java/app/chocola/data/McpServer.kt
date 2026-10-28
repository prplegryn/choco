package app.chocola.data

import org.json.JSONObject

data class McpServer(
    val id: String = newId(), val name: String = "", val transport: String = "http", val url: String = "", val token: String = "",
    val headers: Map<String, String> = emptyMap(), val command: String = "", val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(), val enabled: Boolean = true, val disabledTools: Set<String> = emptySet(),
    val tools: String = "[]", val status: String = "未检查", val protocolVersion: String = ""
) {
    val fingerprint: String get() = ModelAccounts.fingerprint(json().apply { remove("tools"); remove("status"); remove("protocolVersion"); remove("disabledTools"); remove("enabled"); remove("name") }.canonicalJson())
    fun json() = JSONObject().put("id", id).put("name", name).put("transport", transport).put("url", url).put("token", token)
        .put("headers", JSONObject(headers)).put("command", command).put("arguments", jsonArray(arguments)).put("environment", JSONObject(environment))
        .put("enabled", enabled).put("disabledTools", jsonArray(disabledTools.sorted())).put("tools", tools).put("status", status).put("protocolVersion", protocolVersion)
    fun validate() {
        require(name.isNotBlank()) { "请填写服务名称" }
        require(transport in setOf("http", "stdio")) { "请选择 HTTP 或本地服务" }
        if (transport == "http") {
            val endpoint = java.net.URI(url)
            require(endpoint.scheme in setOf("http", "https") && !endpoint.host.isNullOrBlank() && endpoint.userInfo == null && endpoint.fragment == null) { "请填写有效的 MCP 地址" }
        } else require(command.isNotBlank() && !command.contains('\u0000') && arguments.none { it.contains('\u0000') }) { "请填写启动命令" }
        require(headers.keys.none { it.equals("Host", true) || it.startsWith("Mcp-", true) || it.lowercase() in setOf("content-type", "accept", "connection", "content-length") }) { "此请求头由 MCP 管理" }
        require(headers.keys.map { it.lowercase() }.distinct().size == headers.size) { "请求头名称不能重复" }
        require(environment.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) { "环境变量名称不合法" }
    }
    companion object {
        fun from(j: JSONObject) = McpServer(j.optString("id").ifBlank { newId() }, j.optString("name"), j.optString("transport", "http"), j.optString("url"), j.optString("token"),
            j.optJSONObject("headers")?.stringMap().orEmpty(), j.optString("command"), j.optJSONArray("arguments")?.strings().orEmpty(), j.optJSONObject("environment")?.stringMap().orEmpty(),
            j.optBoolean("enabled", true), j.optJSONArray("disabledTools")?.strings()?.toSet().orEmpty(), j.optString("tools", "[]"), j.optString("status", "未检查"), j.optString("protocolVersion"))
        fun import(text: String): List<McpServer> {
            val input = JSONObject(text)
            val servers = input.optJSONObject("mcpServers") ?: error("JSON 需要包含 mcpServers")
            require(servers.length() in 1..32) { "一次导入 1 至 32 个服务" }
            return servers.keys().asSequence().map { name ->
                val source = servers.getJSONObject(name)
                require(source.optString("type") != "sse") { "$name 使用旧 SSE 地址，请改为该服务的 Streamable HTTP 地址" }
                McpServer(name = name, transport = if (source.has("command")) "stdio" else "http", url = source.optString("url"),
                    token = source.optString("token"), headers = source.optJSONObject("headers")?.stringMap().orEmpty(), command = source.optString("command"),
                    arguments = source.optJSONArray("args")?.strings().orEmpty(), environment = source.optJSONObject("env")?.stringMap().orEmpty()).also { it.validate() }
            }.toList()
        }
    }
}

object McpNames {
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val toolPattern = Regex("mcp_[a-f0-9]{40}")
    fun isTool(name: String) = toolPattern.matches(name)
    fun alias(server: McpServer, tool: JSONObject): String = "mcp_" + ModelAccounts.fingerprint(server.id + server.fingerprint + tool.canonicalJson()).take(40)
    fun descriptionKey(tool: JSONObject): String {
        var key = "_chocola_step_description"
        val properties = tool.optJSONObject("inputSchema")?.optJSONObject("properties")
        while (properties?.has(key) == true) key += "_"
        return key
    }
    fun register(server: McpServer) { org.json.JSONArray(server.tools).objects().forEach { tool -> labels[alias(server, tool)] = server.name + " / " + tool.optString("title").ifBlank { tool.optString("name") } } }
    fun label(name: String): String = labels[name] ?: "MCP 工具"
}

/** Stable aliases survive JSON map ordering and a backup on another device. */
internal fun Any?.canonicalJson(): String = when (this) {
    is JSONObject -> keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + opt(it).canonicalJson() }
    is org.json.JSONArray -> (0 until length()).joinToString(",", "[", "]") { opt(it).canonicalJson() }
    is String -> JSONObject.quote(this)
    null, JSONObject.NULL -> "null"
    else -> toString()
}
