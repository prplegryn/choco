package app.chocola.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

@Immutable data class FloatingDesign(val style: String = "glass", val blur: Float = 4f, val refraction: Float = 16f, val white: Float = .8f) {
    fun normalized() = copy(style = if (style == "glass") "glass" else "white",
        blur = blur.takeIf { it.isFinite() }?.coerceIn(0f, 16f) ?: 4f,
        refraction = refraction.takeIf { it.isFinite() }?.coerceIn(4f, 28f) ?: 16f,
        white = white.takeIf { it.isFinite() }?.coerceIn(.08f, .8f) ?: .8f)
    fun json() = JSONObject().put("style", style).put("blur", blur).put("refraction", refraction).put("white", white).put("version", 2)
    companion object {
        fun from(j: JSONObject) = FloatingDesign(if (j.optInt("version") < 2) "glass" else j.optString("style", "glass"), j.optDouble("blur", 4.0).toFloat(),
            j.optDouble("refraction", 16.0).toFloat(), if (j.optInt("version") < 2 && j.optDouble("white", .38) == .38) .8f else j.optDouble("white", .8).toFloat()).normalized()
    }
}
