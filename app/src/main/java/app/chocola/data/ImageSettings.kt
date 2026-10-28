package app.chocola.data

import org.json.JSONObject

data class ImageSettings(val vendorId: String = "", val model: String = "", val transport: String = "images", val routingModel: String = "",
    val size: String = "auto", val quality: String = "auto", val background: String = "auto", val format: String = "png",
    val compression: Int = 100, val count: Int = 1, val inputFidelity: String = "auto", val moderation: String = "auto",
    val timeoutSeconds: Int = 600, val extraBody: String = "{}") {
    fun json() = JSONObject().put("vendorId", vendorId).put("model", model).put("transport", transport).put("routingModel", routingModel)
        .put("size", size).put("quality", quality).put("background", background).put("format", format).put("compression", compression)
        .put("count", count).put("inputFidelity", inputFidelity).put("moderation", moderation).put("timeoutSeconds", timeoutSeconds).put("extraBody", extraBody)
    companion object { fun from(j: JSONObject) = ImageSettings(j.optString("vendorId"), j.optString("model"), j.optString("transport", "images"),
        j.optString("routingModel"), j.optString("size", "auto"), j.optString("quality", "auto"), j.optString("background", "auto"),
        j.optString("format", "png"), j.optInt("compression", 100), j.optInt("count", 1), j.optString("inputFidelity", "auto"),
        j.optString("moderation", "auto"), j.optInt("timeoutSeconds", 600), j.optString("extraBody", "{}")) }
}

object ImageModels {
    fun sameAccount(left: Provider, right: Provider) =
        (left.vendorId.isNotBlank() && left.vendorId == right.vendorId) || ModelAccounts.key(left) == ModelAccounts.key(right)
    fun isImage(provider: Provider) = provider.image != null || isImage(provider.model)
    fun isImage(model: String): Boolean = model.substringAfterLast('/').lowercase().let {
        it.startsWith("gpt-image-") || it == "chatgpt-image-latest" || it.startsWith("dall-e-") || it.startsWith("grok-imagine-image")
    }
    fun family(model: String): String = model.substringAfterLast('/').lowercase().let {
        when { it.startsWith("gpt-image-2.5") -> "gpt25"; it.startsWith("gpt-image-2") -> "gpt2"
            it.startsWith("gpt-image-") || it == "chatgpt-image-latest" -> "gpt1"
            it.startsWith("dall-e-3") -> "dalle3"; it.startsWith("dall-e-2") -> "dalle2"; else -> "custom" }
    }
    fun qualities(model: String): List<String> = when (family(model)) {
        "gpt25" -> listOf("auto", "low", "medium", "high", "xhigh", "max")
        "dalle3" -> listOf("auto", "standard", "hd"); "dalle2" -> listOf("auto", "standard")
        else -> listOf("auto", "low", "medium", "high")
    }
    fun supportsCustomSize(model: String) = family(model) in setOf("gpt2", "gpt25", "custom")
    fun supportsFidelity(model: String) = family(model) == "gpt1"
    fun validate(settings: ImageSettings, model: String = settings.model) {
        require(settings.transport in setOf("images", "responses")) { "请选择有效的绘图接口" }
        require(settings.quality in qualities(model)) { "此图片模型不支持所选质量" }
        require(settings.format in setOf("png", "jpeg", "webp")) { "请选择 PNG、JPEG 或 WebP" }
        require(settings.background in setOf("auto", "opaque", "transparent")) { "背景参数无效" }
        require(settings.format != "jpeg" || settings.background != "transparent") { "透明背景需要 PNG 或 WebP" }
        require(settings.compression in 0..100 && settings.count in 1..10) { "压缩率或生成数量超出范围" }
        require(settings.inputFidelity in setOf("auto", "low", "high") && settings.moderation in setOf("auto", "low")) { "绘图参数无效" }
        require(settings.timeoutSeconds in 60..1800) { "绘图时限应为 60 到 1800 秒" }
        if (settings.size != "auto") {
            val parts = settings.size.lowercase().split('x')
            val width = parts.getOrNull(0)?.toIntOrNull() ?: 0; val height = parts.getOrNull(1)?.toIntOrNull() ?: 0
            require(parts.size == 2 && width > 0 && height > 0 && width <= 8192 && height <= 8192) { "图片尺寸应为宽x高" }
            when (family(model)) {
                "gpt2", "gpt25" -> require(width % 16 == 0 && height % 16 == 0 && maxOf(width, height) <= 3840 &&
                    maxOf(width, height) <= minOf(width, height) * 3 && width.toLong() * height in 655_360L..8_294_400L) { "尺寸须为 16 的倍数，比例不超过 3:1，总像素在 655360 到 8294400 之间，最长边不超过 3840" }
                "gpt1" -> require(settings.size in listOf("1024x1024", "1536x1024", "1024x1536")) { "此模型仅支持 1024 方图或 1536 横竖图" }
                "dalle3" -> require(settings.size in listOf("1024x1024", "1792x1024", "1024x1792")) { "此模型尺寸不受支持" }
                "dalle2" -> require(settings.size in listOf("256x256", "512x512", "1024x1024")) { "此模型尺寸不受支持" }
            }
        }
        val extra = JSONObject(settings.extraBody.ifBlank { "{}" })
        require(extra.keys().asSequence().none { it in setOf("model", "prompt", "input", "image", "images", "mask", "stream", "tools", "tool_choice", "n", "size", "quality", "background", "output_format", "output_compression", "input_fidelity", "moderation") }) { "附加参数不能覆盖绘图模型、输入或已有参数" }
    }
    fun resolve(state: AppState, current: Provider, direct: Boolean = false): Pair<Provider, ImageSettings> {
        val settings = state.preferences.image
        val vendor = settings.vendorId.takeIf { !direct && it.isNotBlank() }?.let { id -> state.vendors.firstOrNull { it.id == id } ?: error("绘图服务商已被删除，请重新选择") }
        val account = vendor?.request() ?: current
        val model = if (direct) current.model else settings.model.ifBlank {
            if (isImage(current)) current.model else state.providers.firstOrNull {
                isImage(it) && sameAccount(it, account)
            }?.model ?: "gpt-image-2"
        }
        val configured = if (direct) current else state.providers.firstOrNull { it.model == model && sameAccount(it, account) }
        if (CodexWire.isCodex(account)) require(model in CodexImageModels.names) { "此 Codex 绘图模型不可用，请重新选择" }
        val profile = configured?.image ?: if (direct) ImageSettings() else settings
        val quality = profile.quality.takeIf { it in qualities(model) } ?: "auto"
        return account.copy(model = model) to profile.copy(model = model, vendorId = settings.vendorId, quality = quality,
            transport = if (CodexWire.isCodex(account)) "images" else profile.transport)
    }
}
