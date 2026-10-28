package app.chocola.data

import org.json.JSONArray
import org.json.JSONObject

data class CodexWindow(val used: Double, val seconds: Long, val resetsAt: Long) {
    val remaining: Int get() = (100.0 - used).toInt().coerceIn(0, 100)
    fun json(): JSONObject = JSONObject().put("used", used).put("seconds", seconds).put("resetsAt", resetsAt)
    companion object { fun from(j: JSONObject) = CodexWindow(j.getDouble("used"), j.getLong("seconds"), j.getLong("resetsAt")) }
}
data class CodexQuota(val windows: List<CodexWindow> = emptyList(), val resetCredits: Int? = null, val checkedAt: Long = 0, val limited: Boolean = false) {
    val fiveHours get() = windows.firstOrNull { it.seconds == 18_000L }
    val weekly get() = windows.firstOrNull { it.seconds == 604_800L }
    fun blocked(now: Long) = (limited && windows.isEmpty()) || windows.any { it.used >= 100 && (it.resetsAt == 0L || it.resetsAt > now) }
    fun json(): JSONObject = JSONObject().put("windows", jsonArray(windows.map { it.json() })).put("resetCredits", resetCredits ?: JSONObject.NULL).put("checkedAt", checkedAt).put("limited", limited)
    companion object {
        fun from(j: JSONObject) = CodexQuota(j.optJSONArray("windows")?.objects()?.map(CodexWindow::from).orEmpty(), if (j.isNull("resetCredits")) null else j.getInt("resetCredits"), j.optLong("checkedAt"), j.optBoolean("limited"))
        fun usage(j: JSONObject, now: Long = System.currentTimeMillis()): CodexQuota {
            val limit = j.optJSONObject("rate_limit") ?: j.optJSONObject("rateLimits") ?: j
            fun window(raw: JSONObject?): CodexWindow? {
                raw ?: return null
                if (!raw.has("used_percent") && !raw.has("usedPercent")) return null
                val used = raw.optDouble("used_percent", raw.optDouble("usedPercent", Double.NaN))
                if (!used.isFinite()) return null
                val duration = raw.optLong("limit_window_seconds", raw.optLong("windowDurationMins") * 60)
                val reset = when { raw.has("reset_at") -> raw.optLong("reset_at") * 1000; raw.has("resetsAt") -> raw.optLong("resetsAt") * 1000; raw.has("reset_after_seconds") -> now + raw.optLong("reset_after_seconds") * 1000; else -> 0L }
                return CodexWindow(used.coerceIn(0.0, 100.0), duration, reset)
            }
            val windows = listOfNotNull(window(limit.optJSONObject("primary_window") ?: limit.optJSONObject("primary")), window(limit.optJSONObject("secondary_window") ?: limit.optJSONObject("secondary")))
            val credits = j.optJSONObject("rate_limit_reset_credits") ?: j.optJSONObject("rateLimitResetCredits")
            val count = credits?.let { when { !it.isNull("available_count") -> it.getInt("available_count"); !it.isNull("availableCount") -> it.getInt("availableCount"); else -> null } }?.coerceAtLeast(0)
            return CodexQuota(windows, count, now, limit.optBoolean("limit_reached") || limit.optBoolean("allowed", true).not())
        }
    }
}

data class CodexAccountInfo(val id: String, val email: String, val plan: String, val enabled: Boolean = true,
    val quota: CodexQuota = CodexQuota(), val refreshing: Boolean = false, val error: String = "", val needsLogin: Boolean = false)
data class CodexLoginInfo(val url: String = "", val code: String = "", val expiresAt: Long = 0, val starting: Boolean = false, val error: String = "")
data class CodexState(val accounts: List<CodexAccountInfo> = emptyList(), val login: CodexLoginInfo? = null, val refreshing: Boolean = false, val error: String = "")

internal data class CodexModel(val slug: String, val context: Int = 0, val efforts: List<String> = emptyList(), val defaultEffort: String = "") {
    fun json(): JSONObject = JSONObject().put("slug", slug).put("context", context).put("efforts", JSONArray(efforts)).put("defaultEffort", defaultEffort)
    fun provider(): Provider {
        val p = Provider(id = "codex_" + ModelAccounts.fingerprint(slug).take(24), model = slug, vendorId = CodexWire.VENDOR,
            protocol = Protocol.RESPONSES, baseUrl = CodexWire.BASE, family = "openai", timeoutSeconds = 600)
        if (ImageModels.isImage(slug)) return p.copy(image = ImageSettings())
        val defaults = ModelCatalog.profile(p)?.apply(p) ?: p.copy(maxTokens = 32768)
        return defaults.copy(protocol = Protocol.RESPONSES, baseUrl = CodexWire.BASE, apiKey = "", headers = emptyMap(), contextWindow = context.takeIf { it > 4096 } ?: defaults.contextWindow,
            reasoningEffort = defaultEffort, reasoningLevels = efforts)
    }
    companion object {
        fun saved(j: JSONObject) = CodexModel(j.getString("slug"), j.optInt("context"), j.optJSONArray("efforts")?.strings().orEmpty(), j.optString("defaultEffort"))
        fun catalog(j: JSONObject): List<CodexModel> = (j.optJSONArray("models") ?: j.optJSONArray("data") ?: JSONArray()).objects().mapNotNull { value ->
            val slug = value.optString("slug", value.optString("id")).trim()
            if (slug.isBlank() || value.optString("visibility") == "hide" || value.optBoolean("allow_only_codex_cloud_agent_orchestrator") || value.optBoolean("allow_only_chatgpt_workspace_executor")) null
            else CodexModel(slug, value.optInt("context_window"), value.optJSONArray("supported_reasoning_levels")?.objects()?.map { it.optString("effort") }?.filter(String::isNotBlank).orEmpty(), value.optString("default_reasoning_level"))
        }.distinctBy { it.slug }
    }
}

/** Images are separate upstream routes, not entries in the Codex text-model discovery response. */
internal object CodexImageModels {
    val names = listOf("gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare", "gpt-image-1.5", "gpt-image-2.5")
    fun include(models: List<CodexModel>): List<CodexModel> = if (models.isEmpty()) models else
        (models + names.map { CodexModel(it) }).distinctBy { it.slug }
}

internal data class CodexCredential(val id: String, val accountId: String, val email: String, val plan: String, val accessToken: String,
    val refreshToken: String, val idToken: String, val expiresAt: Long, val enabled: Boolean = true,
    val quota: CodexQuota = CodexQuota(), val models: List<CodexModel> = emptyList(), val needsLogin: Boolean = false) {
    fun info() = CodexAccountInfo(id, email, plan, enabled, quota, needsLogin = needsLogin)
    override fun toString() = "CodexCredential(id=$id, enabled=$enabled)"
    fun json(): JSONObject = JSONObject().put("id", id).put("accountId", accountId).put("email", email).put("plan", plan).put("accessToken", accessToken)
        .put("refreshToken", refreshToken).put("idToken", idToken).put("expiresAt", expiresAt).put("enabled", enabled).put("quota", quota.json()).put("models", jsonArray(models.map { it.json() })).put("needsLogin", needsLogin)
    companion object {
        fun from(j: JSONObject) = CodexCredential(j.getString("id"), j.getString("accountId"), j.optString("email"), j.optString("plan"), j.getString("accessToken"), j.getString("refreshToken"), j.optString("idToken"), j.getLong("expiresAt"), j.optBoolean("enabled", true), j.optJSONObject("quota")?.let(CodexQuota::from) ?: CodexQuota(), j.optJSONArray("models")?.objects()?.map(CodexModel::saved).orEmpty(), j.optBoolean("needsLogin"))
    }
}

internal object CodexPool {
    fun eligible(accounts: Collection<CodexCredential>, provider: Provider, now: Long, cooling: (String) -> Boolean): List<CodexCredential> = accounts.filter { account ->
        account.enabled && !account.needsLogin && !cooling(account.id) && !account.quota.blocked(now) &&
            account.models.any { it.slug == provider.model && (provider.reasoningEffort.isBlank() || it.efforts.isEmpty() || provider.reasoningEffort in it.efforts) }
    }
}

/** No hosted tools are injected. Only the app's explicitly supplied capabilities leave the device. */
object CodexWire {
    const val VENDOR = "choco_codex"
    const val BASE = "https://chatgpt.com/backend-api/codex"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    // Discovery is version-gated upstream; keep the query and request identity aligned.
    const val CLIENT_VERSION = "0.162.1"
    const val USER_AGENT = "codex-tui/$CLIENT_VERSION (Android; arm64) Choco"
    fun isCodex(p: Provider) = p.vendorId == VENDOR
    fun vendor() = ApiVendor(VENDOR, "Codex", Protocol.RESPONSES, BASE)
    fun normalize(source: JSONObject, dropReasoning: Boolean = false): JSONObject {
        val body = JSONObject(source.toString())
        listOf("max_output_tokens", "max_completion_tokens", "max_tokens", "temperature", "top_p", "truncation", "prompt_cache_options", "prompt_cache_retention", "previous_response_id", "generate", "user", "safety_identifier", "background", "stream_options").forEach(body::remove)
        body.put("stream", true).put("store", false)
        if (!body.has("instructions") || body.isNull("instructions")) body.put("instructions", "")
        if (!body.has("parallel_tool_calls")) body.put("parallel_tool_calls", true)
        val include = body.optJSONArray("include")?.strings().orEmpty().toMutableSet().apply { add("reasoning.encrypted_content") }
        body.put("include", JSONArray(include.toList()))
        val input = body.opt("input")
        val items = if (input is String) listOf(JSONObject().put("role", "user").put("content", input)) else (input as? JSONArray)?.objects().orEmpty()
        body.put("input", jsonArray(items.filterNot { dropReasoning && it.optString("type") == "reasoning" }.map { original ->
            JSONObject(original.toString()).also { item ->
                if (item.optString("role") == "system") item.put("role", "developer")
                if (dropReasoning) item.remove("id")
                if (item.optString("type") == "function_call" && item.optString("arguments").isBlank()) item.put("arguments", "{}")
            }
        }))
        when (body.optString("service_tier")) { "fast" -> body.put("service_tier", "priority"); "priority", "ultrafast", "" -> Unit; else -> body.remove("service_tier") }
        return body
    }
}
