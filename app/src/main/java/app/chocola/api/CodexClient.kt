package app.chocola.api

import app.chocola.data.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CodexDeviceCode(val device: String, val code: String, val interval: Long, val expiresAt: Long) {
    fun json(): JSONObject = JSONObject().put("device", device).put("code", code).put("interval", interval).put("expiresAt", expiresAt)
    companion object { fun from(j: JSONObject) = CodexDeviceCode(j.getString("device"), j.getString("code"), j.getLong("interval"), j.getLong("expiresAt")) }
}
internal class CodexHttpFailure(val status: Int, message: String) : IOException(message)

/** Minimal native port of CLIProxyAPI's device flow and token exchange (see bundled MIT notice). */
internal class CodexClient(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val authBase: String = "https://auth.openai.com", private val backend: String = "https://chatgpt.com/backend-api", private val logs: Diagnostics? = null) {
    companion object { const val LOGIN_URL = "https://auth.openai.com/codex/device" }
    private suspend fun call(request: Request): Pair<Int, JSONObject> {
        val result = client.traced(logs, "codex-account").newCall(request).await()
        result.use { response ->
            val raw = response.body?.string().orEmpty()
            val value = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (!response.isSuccessful && response.code !in setOf(403, 404)) throw failure(response.code, value)
            return response.code to value
        }
    }
    private fun json(path: String, value: JSONObject) = Request.Builder().url(authBase + path).header("Accept", "application/json")
        .post(value.toString().toRequestBody("application/json".toMediaType())).build()
    private fun failure(status: Int, value: JSONObject): CodexHttpFailure {
        // No raw upstream body: auth errors can echo credentials or authorization codes.
        val code = value.optJSONObject("error")?.optString("code").orEmpty().ifBlank { value.optString("error").takeIf { it.length < 100 }.orEmpty() }
        val detail = when {
            code.contains("invalid_grant") || code.contains("refresh_token") -> "登录已失效，请重新添加账号"
            status == 401 -> "账号授权已失效，请重新登录"
            status == 403 -> "服务拒绝访问，请检查账号权限或网络"
            status == 429 -> "请求过于频繁，请稍后再试"
            else -> "服务请求失败（$status）"
        }
        return CodexHttpFailure(status, detail)
    }
    suspend fun begin(): CodexDeviceCode {
        val (status, value) = call(json("/api/accounts/deviceauth/usercode", JSONObject().put("client_id", CodexWire.CLIENT_ID)))
        if (status !in 200..299) throw CodexHttpFailure(status, "设备授权不可用，请在 ChatGPT 安全设置中允许设备代码登录后重试（$status）")
        val device = value.optString("device_auth_id"); val code = value.optString("user_code", value.optString("usercode"))
        require(device.isNotBlank() && code.isNotBlank()) { "授权服务未返回登录代码" }
        Redactor.register(device); Redactor.register(code)
        val interval = value.opt("interval")?.toString()?.toLongOrNull()?.coerceIn(1, 60) ?: 5
        return CodexDeviceCode(device, code, interval * 1000, System.currentTimeMillis() + 15 * 60_000)
    }
    suspend fun poll(pending: CodexDeviceCode): JSONObject? {
        val (status, value) = call(json("/api/accounts/deviceauth/token", JSONObject().put("device_auth_id", pending.device).put("user_code", pending.code)))
        if (status == 403 || status == 404) return null
        if (status !in 200..299) throw failure(status, value)
        val code = value.optString("authorization_code"); val verifier = value.optString("code_verifier")
        require(code.isNotBlank() && verifier.isNotBlank()) { "授权响应不完整，请重新登录" }
        Redactor.register(code); Redactor.register(verifier)
        return exchange(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", CodexWire.CLIENT_ID).add("code", code)
            .add("redirect_uri", "$authBase/deviceauth/callback").add("code_verifier", verifier).build())
    }
    suspend fun refresh(token: String): JSONObject = exchange(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", CodexWire.CLIENT_ID).add("refresh_token", token).add("scope", "openid profile email").build())
    private suspend fun exchange(body: RequestBody): JSONObject {
        val (status, value) = call(Request.Builder().url("$authBase/oauth/token").header("Accept", "application/json").post(body).build())
        if (status !in 200..299) throw failure(status, value)
        listOf("access_token", "refresh_token", "id_token").forEach { if (!value.isNull(it)) Redactor.register(value.getString(it)) }
        require(value.optString("access_token").isNotBlank()) { "授权服务未返回有效凭据" }
        return value
    }
    suspend fun usage(account: CodexCredential): JSONObject = get(account, "/wham/usage").also { require(it.has("rate_limit") || it.has("rateLimits")) { "额度响应不完整，请稍后刷新" } }
    suspend fun models(account: CodexCredential): List<CodexModel> {
        val raw = get(account, "/codex/models?client_version=${CodexWire.CLIENT_VERSION}")
        val entries = (raw.optJSONArray("models") ?: raw.optJSONArray("data"))?.objects().orEmpty()
        val models = CodexModel.catalog(raw)
        logs?.event("codex.models.catalog", mapOf("clientVersion" to CodexWire.CLIENT_VERSION, "received" to entries.size,
            "available" to models.size, "hidden" to entries.count { it.optString("visibility") == "hide" },
            "restricted" to entries.count { it.optBoolean("allow_only_codex_cloud_agent_orchestrator") || it.optBoolean("allow_only_chatgpt_workspace_executor") },
            "models" to models.map { mapOf("model" to it.slug, "efforts" to it.efforts, "default" to it.defaultEffort) }))
        return models
    }
    private suspend fun get(account: CodexCredential, path: String): JSONObject {
        val request = Request.Builder().url(backend + path).header("Authorization", "Bearer ${account.accessToken}")
            .header("Chatgpt-Account-Id", account.accountId).header("User-Agent", CodexWire.USER_AGENT).header("Originator", "codex-tui").header("Accept", "application/json").get().build()
        val (status, value) = call(request)
        if (status !in 200..299) throw failure(status, value)
        return value
    }
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, r, _ -> r.close() } else response.close()
            }
        })
    }
}

internal object CodexTokens {
    private fun claims(token: String): JSONObject = runCatching {
        val parts = token.split('.'); require(parts.size == 3)
        JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
    }.getOrDefault(JSONObject())
    /** Claims identify a TLS-delivered token; this does not pretend to verify a JWT signature. */
    fun credential(value: JSONObject, previous: CodexCredential? = null, now: Long = System.currentTimeMillis()): CodexCredential {
        val access = value.getString("access_token"); val idToken = (if (value.isNull("id_token")) "" else value.getString("id_token")).ifBlank { previous?.idToken.orEmpty() }
        val id = claims(idToken); val accessClaims = claims(access)
        val auth = id.optJSONObject("https://api.openai.com/auth") ?: accessClaims.optJSONObject("https://api.openai.com/auth") ?: JSONObject()
        val account = auth.optString("chatgpt_account_id").ifBlank { previous?.accountId.orEmpty() }
        val email = id.optString("email").ifBlank { accessClaims.optJSONObject("https://api.openai.com/profile")?.optString("email").orEmpty() }.ifBlank { previous?.email.orEmpty() }
        require(account.isNotBlank() && email.isNotBlank()) { "授权未包含账号信息，请重新登录" }
        val refresh = (if (value.isNull("refresh_token")) "" else value.getString("refresh_token")).ifBlank { previous?.refreshToken.orEmpty() }
        require(refresh.isNotBlank()) { "授权未包含续期凭据，请重新登录" }
        val expiry = value.optLong("expires_in").takeIf { it > 0 }?.let { now + it * 1000 } ?: accessClaims.optLong("exp").takeIf { it > 0 }?.let { it * 1000 } ?: (now + 3_600_000)
        return CodexCredential(previous?.id ?: ModelAccounts.fingerprint(account).take(32), account, email, auth.optString("chatgpt_plan_type").ifBlank { previous?.plan.orEmpty() }, access, refresh, idToken, expiry,
            enabled = previous?.enabled ?: true, quota = previous?.quota ?: CodexQuota(), models = previous?.models.orEmpty())
    }
}
