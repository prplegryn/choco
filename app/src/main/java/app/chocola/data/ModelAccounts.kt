package app.chocola.data

import java.net.URI
import java.security.MessageDigest
import java.math.BigDecimal
import org.json.JSONObject

data class ModelAccount(val id: String, val brand: String, val host: String, val models: List<Provider>) {
    val provider get() = models.first()
}
data class ModelBalance(val text: String = "查询中", val detail: String = "", val time: Long = 0, val failed: Boolean = false)

object ModelAccounts {
    fun host(p: Provider) = runCatching { URI(p.baseUrl).host?.lowercase().orEmpty() }.getOrDefault("")
    fun brand(p: Provider): String = when (val host = host(p)) {
        "api.deepseek.com" -> "DeepSeek"
        "api.openai.com" -> "OpenAI"
        "chatgpt.com" -> if (CodexWire.isCodex(p)) "Codex" else "OpenAI"
        "api.anthropic.com" -> "Anthropic"
        "openrouter.ai" -> "OpenRouter"
        "api.siliconflow.cn", "api.siliconflow.com" -> "硅基流动"
        "generativelanguage.googleapis.com" -> "Google"
        "api.moonshot.cn", "api.moonshot.ai" -> "Moonshot"
        "open.bigmodel.cn" -> "智谱"
        "api.z.ai" -> "Z.ai"
        "api.minimax.io", "api.minimax.chat" -> "MiniMax"
        "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com" -> "阿里云百炼"
        "ark.cn-beijing.volces.com" -> "火山引擎"
        "api.x.ai" -> "xAI"
        else -> host.ifBlank { "自定义 API" }
    }
    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun key(p: Provider): String = fingerprint(p.baseUrl.trimEnd('/') + "\n" + p.apiKey + "\n" + p.headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" })
    fun group(providers: List<Provider>): List<ModelAccount> = providers.groupBy(::key).map { (id, models) ->
        ModelAccount(id, brand(models.first()), host(models.first()), models)
    }.sortedWith(compareBy<ModelAccount> { it.brand.lowercase() }.thenBy { it.host })
}

internal object BalanceFormats {
    private fun amount(value: String, currency: String): String {
        val number = value.toBigDecimalOrNull() ?: error("余额返回格式不正确")
        val symbol = when (currency) { "CNY" -> "¥"; "USD" -> "$"; else -> "$currency " }
        return symbol + number.setScale(maxOf(2, number.scale().coerceAtMost(4)), java.math.RoundingMode.HALF_UP).toPlainString()
    }
    fun deepseek(json: JSONObject): ModelBalance {
        val values = json.getJSONArray("balance_infos").objects().map { amount(it.getString("total_balance"), it.getString("currency")) }
        require(values.isNotEmpty()) { "平台未返回余额" }
        return ModelBalance(values.joinToString(" / "), "账户余额", System.currentTimeMillis())
    }
    fun silicon(json: JSONObject, currency: String = "CNY"): ModelBalance {
        require(json.optBoolean("status", json.optInt("code") == 20000)) { "平台暂未提供余额查询" }
        return ModelBalance(amount(json.getJSONObject("data").getString("totalBalance"), currency), "账户余额", System.currentTimeMillis())
    }
    fun openrouter(json: JSONObject): ModelBalance {
        val data = json.getJSONObject("data")
        val effective = data.optJSONObject("effective_budget")
        val remaining = if (effective != null && !effective.isNull("remaining_usd")) effective.get("remaining_usd").toString()
            else if (!data.isNull("limit_remaining")) data.get("limit_remaining").toString() else ""
        return if (remaining.isNotBlank()) ModelBalance(amount(remaining, "USD"), "密钥可用额度", System.currentTimeMillis())
            else ModelBalance("未设额度", "平台未向此密钥提供账户余额", System.currentTimeMillis())
    }
}
