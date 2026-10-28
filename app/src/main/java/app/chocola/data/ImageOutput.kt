package app.chocola.data

import org.json.JSONObject

/** Requested controls and server labels are separate from dimensions read from the actual file. */
data class ImageOutput(val route: String = "", val requestedModel: String = "", val requestedSize: String = "",
    val requestedQuality: String = "", val reportedModel: String = "", val reportedSize: String = "", val reportedQuality: String = "") {
    fun json() = JSONObject().put("route", route).put("requestedModel", requestedModel).put("requestedSize", requestedSize)
        .put("requestedQuality", requestedQuality).put("reportedModel", reportedModel).put("reportedSize", reportedSize).put("reportedQuality", reportedQuality)
    companion object { fun from(j: JSONObject) = ImageOutput(j.optString("route"), j.optString("requestedModel"), j.optString("requestedSize"),
        j.optString("requestedQuality"), j.optString("reportedModel"), j.optString("reportedSize"), j.optString("reportedQuality")) }
}

internal val Attachment.imageSizeMismatch: Boolean get() {
    val requested = output?.requestedSize.orEmpty().lowercase().split('x')
    val expectedWidth = requested.getOrNull(0)?.toIntOrNull() ?: return false
    val expectedHeight = requested.getOrNull(1)?.toIntOrNull() ?: return false
    if (expectedWidth <= 0 || expectedHeight <= 0) return false
    return width > 0 && height > 0 && (width != expectedWidth || height != expectedHeight)
}
internal val Attachment.imageWarning: String get() = if (imageSizeMismatch)
    "接口返回 ${width}×${height}，与请求的 ${output?.requestedSize?.replace('x', '×')} 不一致；原图已保留。" else ""
