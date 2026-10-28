package app.chocola.api

import app.chocola.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BalanceClient(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(), logs: Diagnostics? = null) {
    private val tracedClient = client.traced(logs, "balance")
    suspend fun query(provider: Provider): ModelBalance = withContext(Dispatchers.IO) {
        val host = ModelAccounts.host(provider)
        val url = when (host) {
            "api.deepseek.com" -> "https://api.deepseek.com/user/balance"
            "api.siliconflow.cn", "api.siliconflow.com" -> "https://$host/v1/user/info"
            "openrouter.ai" -> "https://openrouter.ai/api/v1/key"
            else -> return@withContext ModelBalance("余额未提供", "此平台未开放当前 API 密钥的余额接口", System.currentTimeMillis())
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${provider.apiKey}")
        provider.headers.forEach { (name, value) -> request.header(name, value) }
        coroutineScope {
            val call = tracedClient.newCall(request.build())
            val cancel = launch { try { awaitCancellation() } finally { call.cancel() } }
            try { call.execute().use { response ->
                if (response.code in setOf(404, 410)) return@use ModelBalance("余额未提供", "平台未提供余额查询", System.currentTimeMillis())
                require(response.isSuccessful) { if (response.code in setOf(401, 403)) "余额查询未获授权" else "余额查询失败，HTTP ${response.code}" }
                val body = response.body ?: error("平台没有返回余额")
                val source = body.source(); require(!source.request(256_001)) { "余额响应过大" }
                val json = JSONObject(source.readUtf8())
                when (host) { "api.deepseek.com" -> BalanceFormats.deepseek(json); "openrouter.ai" -> BalanceFormats.openrouter(json); else -> BalanceFormats.silicon(json, if (host.endsWith(".com")) "USD" else "CNY") }
            } } finally { cancel.cancel(); call.cancel() }
        }
    }
}
