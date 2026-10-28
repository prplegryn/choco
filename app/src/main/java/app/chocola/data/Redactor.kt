package app.chocola.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CopyOnWriteArraySet

object Redactor {
    private val secrets = CopyOnWriteArraySet<String>()
    private val patterns = listOf(
        Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+"),
        Regex("(?i)(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9_]{12,}|github_pat_[A-Za-z0-9_]{12,})"),
        Regex("(?i)((?:api[_-]?key|token|password|secret|authorization)[\\\"'\\s]*[:=][\\\"'\\s]*)[^\\s\\\"',;}{]+")
    )
    fun register(value: String) { if (value.length >= 4) secrets.add(value) }
    private val sensitive = Regex("(?i)(?:api[_-]?key|x-api-key|(?:access|refresh|auth)[_-]?token|token|password|passwd|private[_-]?key|secret|authorization|proxy-authorization)")
    /** Parse containers first: replacing serialized bytes can corrupt numbers, escapes and delimiters. */
    private fun container(value: String): Any? {
        val trimmed = value.trim()
        if (trimmed.firstOrNull() !in listOf('{', '[')) return null
        val next = trimmed.drop(1).trimStart().firstOrNull() ?: return null
        if (trimmed[0] == '{' && next != '"' && next != '}') return null
        if (trimmed[0] == '[' && next !in "\"{[]-0123456789tfn") return null
        return runCatching {
            val parser = JSONTokener(value); val node = parser.nextValue()
            require(parser.nextClean() == '\u0000')
            node.takeIf { it is JSONObject || it is JSONArray }
        }.getOrNull()
    }
    private fun string(value: String, depth: Int): String = container(value)?.let { sanitize(it, depth + 1).toString() } ?: text(value)
    fun clean(value: String): String = string(value, 0)
    fun containsSecret(value: String): Boolean {
        if (text(value) != value) return true
        val parsed = runCatching { JSONTokener(value).nextValue() }.getOrNull()
        return (parsed is JSONObject || parsed is JSONArray) && sanitize(parsed, 0).toString() != parsed.toString()
    }
    fun json(value: JSONObject): JSONObject = sanitize(value, 0) as JSONObject
    private fun sanitize(value: Any?, depth: Int): Any {
        if (depth > 64) return "[redacted: nesting limit]"
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().also { out -> value.keys().forEach { key ->
                val child = value.opt(key)
                out.put(key, if (sensitive.matches(key) && child is String && child.isNotEmpty()) "[redacted]" else sanitize(child, depth + 1))
            } }
            is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(sanitize(value.opt(i), depth + 1)) }
            is Map<*, *> -> sanitize(JSONObject(value), depth + 1)
            is Collection<*> -> sanitize(JSONArray(value), depth + 1)
            is String -> string(value, depth)
            is Number, is Boolean -> value
            else -> text(value.toString())
        }
    }
    private fun text(value: String): String {
        var text = value
        secrets.sortedByDescending { it.length }.forEach { text = text.replace(it, "[redacted]") }
        patterns.forEach { text = it.replace(text) { m -> if (m.groupValues.size > 1) m.groupValues[1] + "[redacted]" else "[redacted]" } }
        return text
    }
}
