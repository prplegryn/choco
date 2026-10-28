package app.chocola.data

import java.net.URI
import org.json.JSONObject

/** The account exists independently of its models. Model IDs remain stable across migration. */
data class ApiVendor(
    val id: String = newId(), val brand: String = "", val protocol: Protocol = Protocol.CHAT,
    val baseUrl: String = Protocol.CHAT.endpoint, val apiKey: String = "", val headers: Map<String, String> = emptyMap()
) {
    val label: String get() = brand.ifBlank { ModelAccounts.brand(request()) }
    val host: String get() = ModelAccounts.host(request())
    val balanceKey: String get() = ModelAccounts.key(request())
    fun request(): Provider = Provider(id = id, protocol = protocol, baseUrl = baseUrl, apiKey = apiKey, headers = headers, vendorId = id)
    fun attach(model: Provider): Provider = model.copy(name = "", vendorId = id, baseUrl = baseUrl, apiKey = apiKey, headers = headers)
    fun model(name: String = ""): Provider {
        val base = request().copy(id = newId(), model = name)
        return attach(ModelCatalog.profile(base)?.apply(base)?.copy(protocol = protocol) ?: base)
    }
    fun validate() {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null) { "请填写有效的 API 地址" }
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "服务商标识无效" }
        require(headers.keys.all { it.isNotBlank() && '\n' !in it && '\r' !in it } && headers.values.all { '\n' !in it && '\r' !in it }) { "请求头不能包含换行" }
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("brand", brand).put("protocol", protocol.name)
        .put("baseUrl", baseUrl).put("apiKey", apiKey).put("headers", JSONObject(headers))
    companion object {
        fun from(j: JSONObject) = ApiVendor(j.getString("id"), j.optString("brand"), Protocol.valueOf(j.optString("protocol", "CHAT")),
            j.getString("baseUrl"), j.optString("apiKey"), j.optJSONObject("headers")?.stringMap().orEmpty())
    }
}

internal data class VendorMigration(val vendors: List<ApiVendor>, val models: List<Provider>)

internal object VendorAccounts {
    fun migrate(savedVendors: List<ApiVendor>, models: List<Provider>): VendorMigration {
        val vendors = savedVendors.associateByTo(linkedMapOf()) { it.id }
        val migrated = models.map { model ->
            val vendor = vendors[model.vendorId] ?: vendors.values.firstOrNull { model.vendorId.isBlank() && it.balanceKey == ModelAccounts.key(model) }
                ?: ApiVendor(id = model.vendorId.ifBlank { "vendor_" + ModelAccounts.key(model).take(32) }, protocol = model.protocol,
                    baseUrl = model.baseUrl, apiKey = model.apiKey, headers = model.headers).also { vendors[it.id] = it }
            vendor.attach(model)
        }
        return VendorMigration(vendors.values.toList(), migrated)
    }
}
