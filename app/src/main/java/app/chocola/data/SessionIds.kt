package app.chocola.data

import java.security.MessageDigest

/** IDs are also Linux path components. Keep old reports addressable without renaming their data. */
object SessionIds {
    private val current = Regex("[A-Za-z0-9_-]{1,80}")
    private val legacyReport = Regex("scheduled:[A-Za-z0-9_-]{1,80}(?::[A-Za-z0-9_-]{1,80})?")
    fun valid(id: String): Boolean = current.matches(id) || legacyReport.matches(id)
    fun requireValid(id: String) { require(valid(id)) { "无效会话标识" } }
    fun runtimeKey(id: String): String { requireValid(id); return if (current.matches(id)) id else scheduled("legacy", id) }
    fun scheduled(task: String, run: String): String = "scheduled-" + MessageDigest.getInstance("SHA-256")
        .digest("$task\u0000$run".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(40)
    fun credentialCheck(): String = "schedule-check-${newId()}"
}
