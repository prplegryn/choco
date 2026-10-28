package app.chocola.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** Reserved output slots are metadata, never fake files or attachments sent to a model. */
@Immutable data class ImageBatch(val count: Int = 1, val aspectRatio: Float = 1f) {
    fun json() = JSONObject().put("count", count).put("aspectRatio", aspectRatio.toDouble())
    companion object {
        /** Only explicit image quantities override saved defaults; scene objects are not image counts. */
        fun requestedCount(prompt: String): Int? {
            val matches = Regex("([0-9一二两三四五六七八九十]{1,3})\\s*(?:张|幅)\\s*(?:图片|图像|插画|壁纸|海报|照片|图)|(?:生成|绘制|画|制作)\\s*([0-9一二两三四五六七八九十]{1,3})\\s*(?:张|幅)(?=\\s*(?:[，,。.!！;；:：\\n]|$))").findAll(prompt)
            val value = matches.lastOrNull()?.let { it.groupValues[1].ifBlank { it.groupValues[2] } } ?: return null
            val number = value.toIntOrNull() ?: if (value == "十") 10 else value.singleOrNull()?.let { "一二三四五六七八九".indexOf(if (it == '两') '二' else it).takeIf { index -> index >= 0 }?.plus(1) }
            require(number != null && number in 1..10) { "每批支持 1 到 10 张图片" }
            return number
        }
        fun from(j: JSONObject) = ImageBatch(j.optInt("count", 1).coerceIn(1, 10), j.optDouble("aspectRatio", 1.0).toFloat().takeIf { it.isFinite() && it > 0 } ?: 1f)
        fun forSettings(settings: ImageSettings): ImageBatch {
            val size = settings.size.split('x')
            val width = size.getOrNull(0)?.toFloatOrNull() ?: 0f
            val height = size.getOrNull(1)?.toFloatOrNull() ?: 0f
            return ImageBatch(settings.count, if (width > 0 && height > 0) width / height else 1f)
        }
    }
}
