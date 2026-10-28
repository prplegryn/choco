package app.chocola.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import kotlin.math.max

@Immutable data class ChatBackground(
    val preset: String = "paper", val image: String = "", val imageTone: String = "#9BA3AA",
    val color: String = "#FAFAF9", val colorEnd: String = "#FAFAF9", val angle: Float = 90f,
    val blur: Float = 0f, val brightness: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f, val warmth: Float = 0f,
    val veil: Float = 0f, val veilColor: String = "#FFFFFF", val horizontal: Float = .5f, val zoom: Float = 1f,
    val edgeOpacity: Float = 1f, val edgeDepth: Float = 112f, val text: String = "auto"
) {
    fun normalized(): ChatBackground {
        fun f(v: Float, low: Float, high: Float, default: Float) = if (v.isFinite()) v.coerceIn(low, high) else default
        fun c(v: String, default: String) = v.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.uppercase() ?: default
        return copy(color = c(color, "#FAFAF9"), colorEnd = c(colorEnd, "#FAFAF9"), imageTone = c(imageTone, "#9BA3AA"), veilColor = c(veilColor, "#FFFFFF"),
            angle = f(angle, 0f, 360f, 90f), blur = f(blur, 0f, 48f, 0f), brightness = f(brightness, -.65f, .65f, 0f),
            contrast = f(contrast, .5f, 1.8f, 1f), saturation = f(saturation, 0f, 1.8f, 1f), warmth = f(warmth, -.5f, .5f, 0f),
            veil = f(veil, 0f, 1f, 0f), horizontal = f(horizontal, 0f, 1f, .5f), zoom = f(zoom, 1f, 1.8f, 1f),
            edgeOpacity = f(edgeOpacity, 0f, 1f, 1f), edgeDepth = f(edgeDepth, 48f, 220f, 112f), text = text.takeIf { it in setOf("auto", "dark", "light") } ?: "auto")
    }
    fun json() = JSONObject().put("preset", preset).put("image", image).put("imageTone", imageTone)
        .put("color", color).put("colorEnd", colorEnd).put("angle", angle).put("blur", blur).put("brightness", brightness)
        .put("contrast", contrast).put("saturation", saturation).put("warmth", warmth).put("veil", veil).put("veilColor", veilColor)
        .put("horizontal", horizontal).put("zoom", zoom).put("edgeOpacity", edgeOpacity).put("edgeDepth", edgeDepth).put("text", text)
    companion object {
        val presets = linkedMapOf("paper" to "浅纸", "mist" to "柔雾", "warm" to "暖光", "cool" to "清蓝", "dusk" to "暮色", "night" to "夜幕", "photo" to "原图", "frost" to "磨砂", "mono" to "黑白")
        fun preset(key: String, current: ChatBackground = ChatBackground()): ChatBackground {
            val base = ChatBackground(image = current.image, imageTone = current.imageTone, horizontal = current.horizontal, zoom = current.zoom)
            return when (key) {
                "mist" -> base.copy(preset = key, color = "#E7ECF1", colorEnd = "#F5F1EC", blur = 22f, saturation = .65f, veil = .60f)
                "warm" -> base.copy(preset = key, color = "#EFE2D2", colorEnd = "#FCF8F2", warmth = .18f, saturation = .75f, veil = .35f, veilColor = "#FFF4E4")
                "cool" -> base.copy(preset = key, color = "#DCE7F1", colorEnd = "#F0F4F9", warmth = -.12f, saturation = .75f, veil = .35f, veilColor = "#EAF2FC")
                "dusk" -> base.copy(preset = key, color = "#7D819D", colorEnd = "#E8D4D1", brightness = -.06f, veil = .3f, veilColor = "#E8D4D1")
                "night" -> base.copy(preset = key, color = "#151B28", colorEnd = "#323D52", brightness = -.26f, saturation = .65f, veil = .28f, veilColor = "#182232", text = "light")
                "photo" -> base.copy(preset = key, veil = .12f)
                "frost" -> base.copy(preset = key, color = "#E2E6EB", colorEnd = "#F6F5F2", blur = 32f, saturation = .45f, veil = .45f)
                "mono" -> base.copy(preset = key, color = "#DBDDDF", colorEnd = "#F4F4F4", saturation = 0f, contrast = .85f, veil = .25f)
                else -> base.copy(image = "")
            }
        }
        fun from(j: JSONObject): ChatBackground {
            val d = ChatBackground()
            fun f(key: String, value: Float) = j.optDouble(key, value.toDouble()).toFloat()
            return ChatBackground(j.optString("preset", d.preset), j.optString("image"), j.optString("imageTone", d.imageTone), j.optString("color", d.color), j.optString("colorEnd", d.colorEnd),
                f("angle", d.angle), f("blur", d.blur), f("brightness", d.brightness), f("contrast", d.contrast), f("saturation", d.saturation), f("warmth", d.warmth),
                f("veil", d.veil), j.optString("veilColor", d.veilColor), f("horizontal", d.horizontal), f("zoom", d.zoom), f("edgeOpacity", d.edgeOpacity), f("edgeDepth", d.edgeDepth), j.optString("text", d.text)).normalized()
        }
    }
}

internal data class BackgroundCrop(val width: Float, val height: Float, val left: Float, val top: Float)
internal object BackgroundGeometry {
    /** Fill height first. Extra-wide images lose only their sides; portrait images also cover width. */
    fun crop(imageWidth: Float, imageHeight: Float, width: Float, height: Float, horizontal: Float, zoom: Float): BackgroundCrop {
        val scale = max(height / imageHeight.coerceAtLeast(1f), width / imageWidth.coerceAtLeast(1f)) * zoom.coerceIn(1f, 1.8f)
        val w = imageWidth * scale; val h = imageHeight * scale
        return BackgroundCrop(w, h, (width - w) * horizontal.coerceIn(0f, 1f), (height - h) / 2)
    }
}
