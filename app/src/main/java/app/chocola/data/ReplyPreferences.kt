package app.chocola.data

import org.json.JSONObject

/** Generation preferences are independent of font size and other display choices. */
data class ReplyPreferences(
    val tone: String = "natural", val warmth: Int = 1, val enthusiasm: Int = 1,
    val detail: Int = 1, val structure: Int = 1, val emoji: Int = 1
) {
    fun json() = JSONObject().put("tone", tone).put("warmth", warmth).put("enthusiasm", enthusiasm)
        .put("detail", detail).put("structure", structure).put("emoji", emoji)
    fun prompt(): String = buildString {
        append(when (tone) {
            "professional" -> "用准确、沉稳的专业语气交流，必要时保留术语并清楚解释。"
            "friendly" -> "像熟悉的同伴一样轻松友好地交流，表达坦诚，避免刻意套近乎。"
            "direct" -> "直截了当地给出判断、结果和行动，少用铺垫。"
            "curious" -> "带着好奇心探索问题，用清楚的例子解释原理，避免无关追问。"
            else -> "语气自然，随场景调整正式程度。"
        })
        append(when (warmth) { 0 -> "保持客观克制。"; 2 -> "多一些体贴、亲切和对处境的理解，避免奉承和重复共情。"; else -> "适度亲切，不使用机械化客套。" })
        append(when (enthusiasm) { 0 -> "表达平静，少用感叹。"; 2 -> "表达积极、有活力，适度鼓励，不夸大结果。"; else -> "保持适度积极，情绪符合话题。" })
        append(when (detail) { 0 -> "优先简短回答，保留必要数据和限制。"; 2 -> "需要时充分说明依据、推导、步骤和例子。"; else -> "详略与问题复杂度匹配。" })
        append(when (structure) { 0 -> "优先连贯的自然段，仅在确有帮助时使用标题或列表。"; 2 -> "多主题回答使用清晰的短标题和短列表，层级最多两层。"; else -> "根据内容选择自然段、短列表和必要的简短标题。" })
        append(when (emoji) { 0 -> "不使用表情符号。"; 2 -> "轻松话题可适量使用贴切的表情符号，不以表情作每段或每条的项目符号。"; else -> "表情符号少量使用，技术内容和严肃话题保持克制。" })
        append("这些偏好只调整表达，不删减专业能力、事实依据或执行功能；用户当次要求优先。")
    }
    companion object {
        fun from(j: JSONObject) = ReplyPreferences(j.optString("tone", "natural").takeIf { it in setOf("natural", "professional", "friendly", "direct", "curious") } ?: "natural",
            j.optInt("warmth", 1).coerceIn(0, 2), j.optInt("enthusiasm", 1).coerceIn(0, 2), j.optInt("detail", 1).coerceIn(0, 2),
            j.optInt("structure", 1).coerceIn(0, 2), j.optInt("emoji", 1).coerceIn(0, 2))
        fun legacy(style: String) = ReplyPreferences(detail = when (style) { "简洁" -> 0; "细致" -> 2; else -> 1 })
    }
}
