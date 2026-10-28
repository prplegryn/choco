package app.chocola.api

import app.chocola.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal interface McpTransport : Closeable {
    suspend fun exchange(message: JSONObject, version: String, headers: Map<String, String> = emptyMap(), timeout: Long = 120_000): JSONObject?
}

internal class McpHttpTransport(private val server: McpServer,
    private val client: OkHttpClient = sharedClient, logs: app.chocola.data.Diagnostics? = null, session: String = "") : McpTransport {
    private val tracedClient = client.traced(logs, "mcp", session)
    private var session = ""
    private var lastVersion = McpProtocol.Modern
    private val active = ConcurrentHashMap.newKeySet<Call>()
    override suspend fun exchange(message: JSONObject, version: String, headers: Map<String, String>, timeout: Long): JSONObject? = withContext(Dispatchers.IO) {
        lastVersion = version
        val request = Request.Builder().url(server.url).header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", version)
        server.headers.forEach { (key, value) -> request.header(key, value) }
        if (server.token.isNotBlank()) request.header("Authorization", "Bearer ${server.token}")
        if (session.isNotBlank() && version != McpProtocol.Modern) request.header("Mcp-Session-Id", session)
        if (version == McpProtocol.Modern) {
            request.header("Mcp-Method", message.getString("method"))
            message.optJSONObject("params")?.optString("name")?.takeIf { it.isNotBlank() }?.let { request.header("Mcp-Name", McpProtocol.headerValue(it)) }
            message.optJSONObject("params")?.optString("uri")?.takeIf { it.isNotBlank() }?.let { request.header("Mcp-Name", McpProtocol.headerValue(it)) }
            headers.forEach { (key, value) -> request.header(key, value) }
        }
        request.post(message.toString().toRequestBody("application/json".toMediaType()))
        coroutineScope {
            val call = tracedClient.newCall(request.build()).also { it.timeout().timeout(timeout, TimeUnit.MILLISECONDS); active += it }
            val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
            var completed = false
            try { call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.source()?.let { source -> if (!source.request(64_001)) source.readUtf8() else "" }.orEmpty()
                    val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
                    throw McpFailure(error?.optInt("code") ?: -1,
                        when (response.code) { 401, 403 -> "MCP 服务未授权，请检查令牌或请求头"; 404 -> "MCP 地址或会话已失效，请检查服务后重试"; else -> "MCP 请求失败，HTTP ${response.code}" }, error?.optJSONObject("data"), response.code)
                }
                if (!message.has("id")) return@use null
                response.header("Mcp-Session-Id")?.let { require(it.length <= 512 && it.all { ch -> ch.code in 33..126 }) { "MCP 会话标识无效" }; session = it }
                val body = response.body ?: error("MCP 服务没有返回内容")
                if (response.header("Content-Type").orEmpty().substringBefore(';').trim().equals("text/event-stream", true)) {
                    readSse(body.source(), message.get("id").toString())
                } else {
                    val source = body.source(); require(!source.request(8_000_001)) { "MCP 响应过大" }
                    val value = JSONObject(source.readUtf8())
                    require(value.opt("id")?.toString() == message.get("id").toString()) { "MCP 响应编号不匹配" }
                    value
                }.also { completed = true }
            } } finally {
                if (!completed && message.has("id") && version != McpProtocol.Modern && (!currentCoroutineContext().isActive || call.isCanceled())) {
                    val cancel = Request.Builder().url(server.url).header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", version)
                    server.headers.forEach { (key, value) -> cancel.header(key, value) }
                    if (session.isNotBlank()) cancel.header("Mcp-Session-Id", session)
                    if (server.token.isNotBlank()) cancel.header("Authorization", "Bearer ${server.token}")
                    cancel.post(McpProtocol.request(null, "notifications/cancelled", JSONObject().put("requestId", message.get("id")), version).toString().toRequestBody("application/json".toMediaType()))
                    tracedClient.newCall(cancel.build()).apply { timeout().timeout(10, TimeUnit.SECONDS) }.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) = Unit
                        override fun onResponse(call: Call, response: Response) { response.close() }
                    })
                }
                watcher.cancel(); active -= call; call.cancel()
            }
        }
    }
    override fun close() {
        active.forEach { it.cancel() }; active.clear()
        val closingSession = session; session = ""
        if (closingSession.isNotBlank() && lastVersion != McpProtocol.Modern) {
            val request = Request.Builder().url(server.url).delete().header("Mcp-Session-Id", closingSession).header("MCP-Protocol-Version", lastVersion)
            server.headers.forEach { (key, value) -> request.header(key, value) }
            if (server.token.isNotBlank()) request.header("Authorization", "Bearer ${server.token}")
            tracedClient.newCall(request.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) = Unit
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
    }

    companion object {
        private val sharedClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        /** SSE frames may carry multiple data lines, comments, or progress before the response. */
        internal fun readSse(source: okio.BufferedSource, id: String): JSONObject {
            val event = StringBuilder(); var bytes = 0L
            while (true) {
                val line = source.readUtf8LineStrict(8_000_000).also { bytes += it.length }
                require(bytes <= 12_000_000) { "MCP 流式响应过大" }
                if (line.isEmpty()) {
                    if (event.isNotEmpty()) {
                        val message = JSONObject(event.toString().trimEnd('\n')); event.setLength(0)
                        if (message.has("id") && message.opt("id").toString() == id && (message.has("result") || message.has("error"))) return message
                    }
                } else if (line.startsWith("data:")) event.append(line.substringAfter(':').removePrefix(" ")).append('\n')
            }
        }
    }
}

internal class McpStdioTransport(private val process: Process, private val onError: (String) -> Unit = {}) : McpTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val writeLock = Mutex()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    init {
        scope.launch {
            try {
                process.inputStream.source().buffer().use { input ->
                    while (isActive) {
                        val line = input.readUtf8LineStrict(8_000_000)
                        if (line.isBlank()) continue
                        val message = JSONObject(line)
                        val id = message.opt("id")?.toString()
                        if (id != null && (message.has("result") || message.has("error"))) pending[id]?.complete(message)
                        else if (id != null && message.has("method")) {
                            write(JSONObject().put("jsonrpc", "2.0").put("id", message.get("id")).put("error", JSONObject()
                                .put("code", -32601).put("message", "Client capability not supported")))
                        }
                    }
                }
            } catch (e: Exception) { pending.values.forEach { it.completeExceptionally(IllegalStateException("MCP 本地服务已断开，请检查命令和依赖", e)) } }
        }
        scope.launch {
            runCatching { process.errorStream.source().buffer().use { source ->
                var lines = 0
                while (isActive) { val line = source.readUtf8LineStrict(8192); if (lines++ < 32) onError(Redactor.clean(line).take(2000)) }
            } }
        }
    }
    private suspend fun write(message: JSONObject) = writeLock.withLock { writer.write(message.toString()); writer.newLine(); writer.flush() }
    override suspend fun exchange(message: JSONObject, version: String, headers: Map<String, String>, timeout: Long): JSONObject? = withContext(Dispatchers.IO) {
        require(process.isAlive) { "MCP 本地服务已经退出，请检查启动命令" }
        if (!message.has("id")) { write(message); return@withContext null }
        val id = message.get("id").toString()
        val answer = CompletableDeferred<JSONObject>(); pending[id] = answer
        try {
            write(message)
            withTimeout(timeout) { answer.await() }
        } finally {
            pending.remove(id)
            if (!answer.isCompleted) withContext(NonCancellable) {
                runCatching { write(McpProtocol.request(null, "notifications/cancelled", JSONObject().put("requestId", message.get("id")), version)) }
            }
        }
    }
    override fun close() {
        runCatching { process.outputStream.close() }
        process.destroy()
        scope.cancel()
        if (process.isAlive) process.destroyForcibly()
        pending.values.forEach { it.cancel() }; pending.clear()
    }
}

internal class McpConnection(private val wire: McpTransport) : Closeable {
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    var version: String = McpProtocol.Modern; private set
    var capabilities: JSONObject = JSONObject(); private set
    suspend fun request(method: String, params: JSONObject = JSONObject(), headers: Map<String, String> = emptyMap(), timeout: Long = 120_000): JSONObject =
        McpProtocol.result(requireNotNull(wire.exchange(McpProtocol.request(sequence.incrementAndGet(), method, params, version), version, headers, timeout)))
    suspend fun initialize() {
        val discovered = try { request("server/discover", timeout = 8_000) } catch (e: TimeoutCancellationException) { null }
            catch (e: McpFailure) {
                if (e.httpStatus in setOf(401, 403) || e.httpStatus >= 500) throw e
                if (e.code == -32022) {
                    val supported = e.data?.optJSONArray("supported")?.strings().orEmpty()
                    require(McpProtocol.Modern in supported || supported.any { it in McpProtocol.versions }) { "MCP 协议版本不兼容" }
                    if (McpProtocol.Modern in supported) request("server/discover", timeout = 8_000) else null
                } else null
            }
        if (discovered != null && McpProtocol.Modern in discovered.optJSONArray("supportedVersions")?.strings().orEmpty()) {
            capabilities = discovered.optJSONObject("capabilities") ?: JSONObject(); return
        }
        version = McpProtocol.Legacy
        val result = request("initialize", JSONObject().put("protocolVersion", version).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Choco").put("version", app.chocola.BuildConfig.VERSION_NAME)), timeout = 45_000)
        version = result.getString("protocolVersion")
        require(version in McpProtocol.versions && version != McpProtocol.Modern) { "MCP 服务返回了不支持的协议版本" }
        capabilities = result.optJSONObject("capabilities") ?: JSONObject()
        wire.exchange(McpProtocol.request(null, "notifications/initialized", JSONObject(), version), version, timeout = 15_000)
    }
    suspend fun tools(): List<JSONObject> {
        val tools = mutableListOf<JSONObject>()
        if (capabilities.has("tools")) {
            var cursor = ""; val cursors = mutableSetOf<String>()
            do {
                val result = request("tools/list", JSONObject().also { if (cursor.isNotBlank()) it.put("cursor", cursor) })
                result.optJSONArray("tools")?.objects()?.forEach { tool ->
                    require(tool.getString("name").length in 1..256 && tool.has("inputSchema")) { "MCP 工具定义无效" }
                    if (version != McpProtocol.Modern || runCatching { McpProtocol.parameterHeaders(tool.getJSONObject("inputSchema"), JSONObject()) }.isSuccess)
                        tools += JSONObject(tool.toString()).put("chocolaMethod", "tools/call")
                }
                require(tools.size <= 512) { "MCP 服务工具过多" }
                cursor = result.optString("nextCursor")
                require(cursor.isBlank() || cursors.add(cursor)) { "MCP 分页游标重复" }
            } while (cursor.isNotBlank())
        }
        fun builtIn(name: String, title: String, method: String, properties: JSONObject, required: List<String> = emptyList()) =
            JSONObject().put("name", name).put("title", title).put("description", title).put("chocolaMethod", method)
                .put("inputSchema", JSONObject().put("type", "object").put("properties", properties).put("required", jsonArray(required)))
        if (capabilities.has("resources")) {
            tools += builtIn("chocola_resources_list", "列出资源", "resources/list", JSONObject().put("cursor", JSONObject().put("type", "string")))
            tools += builtIn("chocola_resources_read", "读取资源", "resources/read", JSONObject().put("uri", JSONObject().put("type", "string")), listOf("uri"))
        }
        if (capabilities.has("prompts")) {
            tools += builtIn("chocola_prompts_list", "列出提示模板", "prompts/list", JSONObject().put("cursor", JSONObject().put("type", "string")))
            tools += builtIn("chocola_prompts_get", "读取提示模板", "prompts/get", JSONObject().put("name", JSONObject().put("type", "string"))
                .put("arguments", JSONObject().put("type", "object").put("additionalProperties", JSONObject().put("type", "string"))), listOf("name"))
        }
        require(tools.map { it.getString("name") }.distinct().size == tools.size) { "MCP 工具名称重复" }
        return tools
    }
    suspend fun call(tool: JSONObject, arguments: JSONObject): JSONObject {
        val method = tool.getString("chocolaMethod")
        val params = if (method == "tools/call") JSONObject().put("name", tool.getString("name")).put("arguments", arguments) else arguments
        val headers = if (version == McpProtocol.Modern && method == "tools/call") McpProtocol.parameterHeaders(tool.getJSONObject("inputSchema"), arguments) else emptyMap()
        val result = request(method, params, headers)
        require(result.optString("resultType") != "input_required") { "此 MCP 操作需要额外交互授权，请在服务端完成授权后重试" }
        return result
    }
    override fun close() = wire.close()
}
