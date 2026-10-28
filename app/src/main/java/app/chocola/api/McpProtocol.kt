package app.chocola.api

import app.chocola.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal class McpFailure(val code: Int, message: String, val data: JSONObject? = null, val httpStatus: Int = 0) : Exception(message)

internal object McpProtocol {
    const val Modern = "2026-07-28"
    const val Legacy = "2025-11-25"
    val versions = setOf(Modern, Legacy, "2025-06-18", "2025-03-26", "2024-11-05")
    fun request(id: Long?, method: String, params: JSONObject, version: String): JSONObject {
        val arguments = JSONObject(params.toString())
        if (version == Modern) arguments.put("_meta", (arguments.optJSONObject("_meta") ?: JSONObject())
            .put("io.modelcontextprotocol/protocolVersion", version)
            .put("io.modelcontextprotocol/clientInfo", JSONObject().put("name", "Choco").put("version", app.chocola.BuildConfig.VERSION_NAME))
            .put("io.modelcontextprotocol/clientCapabilities", JSONObject()))
        return JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", arguments).also { if (id != null) it.put("id", id) }
    }
    fun result(message: JSONObject): JSONObject {
        message.optJSONObject("error")?.let { throw McpFailure(it.optInt("code"), Redactor.clean(it.optString("message", "MCP 请求失败")), it.optJSONObject("data")) }
        return message.optJSONObject("result") ?: error("MCP 未返回有效结果")
    }
    fun headerValue(value: String): String = if (value != value.trim() || value.any { it.code !in 32..126 } || (value.startsWith("=?base64?") && value.endsWith("?=")))
        "=?base64?${Base64.getEncoder().encodeToString(value.toByteArray())}?=" else value
    /** Validate every annotation, including ones outside statically reachable properties. */
    fun parameterHeaders(schema: JSONObject, arguments: JSONObject): Map<String, String> {
        val result = linkedMapOf<String, String>(); val seen = mutableSetOf<String>()
        fun visit(node: Any?, value: Any?, reachable: Boolean) {
            if (node is JSONArray) { node.objects().forEach { visit(it, null, false) }; return }
            if (node !is JSONObject) return
            if (node.has("x-mcp-header")) {
                val name = node.getString("x-mcp-header")
                require(reachable && name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && seen.add(name.lowercase())) { "MCP 工具的请求头映射无效" }
                val type = node.optString("type")
                require(type in setOf("string", "integer", "boolean")) { "MCP 请求头映射只能用于字符串、整数或布尔值" }
                if (value != null && value != JSONObject.NULL) {
                    require((type == "string" && value is String) || (type == "boolean" && value is Boolean) ||
                        (type == "integer" && value is Number && value.toDouble() % 1.0 == 0.0 && kotlin.math.abs(value.toDouble()) <= 9007199254740991.0)) { "MCP 请求头参数类型无效" }
                    result["Mcp-Param-$name"] = headerValue(value.toString())
                }
            }
            node.keys().forEach { key ->
                val child = node.opt(key)
                if (key == "properties" && reachable && child is JSONObject) child.keys().forEach { name -> visit(child.opt(name), (value as? JSONObject)?.opt(name), true) }
                else if (child is JSONObject || child is JSONArray) visit(child, null, false)
            }
        }
        visit(schema, arguments, true)
        return result
    }
}
