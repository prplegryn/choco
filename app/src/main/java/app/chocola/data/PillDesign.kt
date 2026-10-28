package app.chocola.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** Portable, versioned visual configuration. It contains no account or conversation data. */
@Immutable data class PillDesign(
    val style: String = "sheen", val height: Float = 32f, val minWidth: Float = 78f, val maxWidth: Float = 260f,
    val autoWidth: Boolean = true, val fixedWidth: Float = 156f, val padding: Float = 13f, val corner: Float = 16f,
    val fontSize: Float = 12f, val fontWeight: Int = 500, val letterSpacing: Float = 0f,
    val ink: String = "#222326", val paper: String = "#FFFFFF", val lightA: String = "#8993A2", val lightB: String = "#FFFFFF", val lightC: String = "#BCC3CD",
    val period: Int = 1800, val strength: Float = .65f, val band: Float = .72f, val angle: Float = -14f, val direction: String = "forward",
    val textEffect: String = "fade", val textDuration: Int = 600, val feather: Float = 22f, val textDirection: String = "forward",
    val widthDuration: Int = 560, val widthCurve: String = "smooth", val stiffness: Float = 300f, val damping: Float = 1f,
    val appearanceDuration: Int = 240, val entrance: String = "lift", val travel: Float = 4f, val startScale: Float = .98f,
    val shadowBlur: Float = 20f, val shadowAlpha: Float = .08f, val shadowOffset: Float = 2f, val shadowColor: String = "#0B0C10"
) {
    fun normalized(): PillDesign {
        fun f(v: Float, min: Float, max: Float, default: Float) = if (v.isFinite()) v.coerceIn(min, max) else default.coerceIn(min, max)
        fun color(v: String, default: String) = v.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?")) }?.uppercase() ?: default
        val low = f(minWidth, 48f, 280f, 78f)
        return copy(style = StatusEffects.normalize(style), height = f(height, 24f, 56f, 32f), minWidth = low, maxWidth = f(maxWidth, low, 340f, 260f),
            fixedWidth = f(fixedWidth, 64f, 340f, 156f), padding = f(padding, 4f, 32f, 13f), corner = f(corner, 0f, 28f, 16f),
            fontSize = f(fontSize, 10f, 20f, 12f), fontWeight = fontWeight.coerceIn(300, 700), letterSpacing = f(letterSpacing, -.5f, 2f, 0f),
            ink = color(ink, "#222326"), paper = color(paper, "#FFFFFF"), lightA = color(lightA, "#8993A2"), lightB = color(lightB, "#FFFFFF"), lightC = color(lightC, "#BCC3CD"),
            period = period.coerceIn(600, 12000), strength = f(strength, 0f, 1f, .65f), band = f(band, .15f, 1.8f, .72f), angle = f(angle, -65f, 65f, -14f),
            direction = direction.takeIf { it in setOf("forward", "reverse", "alternate") } ?: "forward",
            textEffect = textEffect.takeIf { it in setOf("glide", "fade", "still") } ?: "fade", textDuration = textDuration.coerceIn(100, 1000), feather = f(feather, 4f, 80f, 22f),
            textDirection = textDirection.takeIf { it in setOf("forward", "reverse") } ?: "forward", widthDuration = widthDuration.coerceIn(160, 1200),
            widthCurve = widthCurve.takeIf { it in setOf("smooth", "ease", "linear", "spring") } ?: "smooth", stiffness = f(stiffness, 80f, 1000f, 300f), damping = f(damping, .55f, 1.3f, 1f),
            appearanceDuration = appearanceDuration.coerceIn(100, 900), entrance = entrance.takeIf { it in setOf("lift", "scale", "fade", "still") } ?: "lift",
            travel = f(travel, 0f, 24f, 4f), startScale = f(startScale, .75f, 1f, .98f), shadowBlur = f(shadowBlur, 1f, 40f, 20f),
            shadowAlpha = f(shadowAlpha, 0f, .35f, .08f), shadowOffset = f(shadowOffset, -8f, 12f, 2f), shadowColor = color(shadowColor, "#0B0C10"))
    }
    fun json(): JSONObject = JSONObject()
        .put("motionVersion", 2).put("style", style).put("height", height).put("minWidth", minWidth).put("maxWidth", maxWidth).put("autoWidth", autoWidth).put("fixedWidth", fixedWidth).put("padding", padding).put("corner", corner)
        .put("fontSize", fontSize).put("fontWeight", fontWeight).put("letterSpacing", letterSpacing).put("ink", ink).put("paper", paper).put("lightA", lightA).put("lightB", lightB).put("lightC", lightC)
        .put("period", period).put("strength", strength).put("band", band).put("angle", angle).put("direction", direction)
        .put("textEffect", textEffect).put("textDuration", textDuration).put("feather", feather).put("textDirection", textDirection)
        .put("widthDuration", widthDuration).put("widthCurve", widthCurve).put("stiffness", stiffness).put("damping", damping)
        .put("appearanceDuration", appearanceDuration).put("entrance", entrance).put("travel", travel).put("startScale", startScale)
        .put("shadowBlur", shadowBlur).put("shadowAlpha", shadowAlpha).put("shadowOffset", shadowOffset).put("shadowColor", shadowColor)
    fun export(): String = JSONObject().put("schema", "chocola.status.v1").put("configuration", normalized().json()).toString(2)
    companion object {
        fun from(j: JSONObject): PillDesign {
            val d = PillDesign()
            fun f(key: String, default: Float) = j.optDouble(key, default.toDouble()).toFloat()
            return PillDesign(style = j.optString("style", d.style), height = f("height", d.height), minWidth = f("minWidth", d.minWidth), maxWidth = f("maxWidth", d.maxWidth),
                autoWidth = j.optBoolean("autoWidth", d.autoWidth), fixedWidth = f("fixedWidth", d.fixedWidth), padding = f("padding", d.padding), corner = f("corner", d.corner),
                fontSize = f("fontSize", d.fontSize), fontWeight = j.optInt("fontWeight", d.fontWeight), letterSpacing = f("letterSpacing", d.letterSpacing),
                ink = j.optString("ink", d.ink), paper = j.optString("paper", d.paper), lightA = j.optString("lightA", d.lightA), lightB = j.optString("lightB", d.lightB), lightC = j.optString("lightC", d.lightC),
                period = j.optInt("period", d.period), strength = f("strength", d.strength), band = f("band", d.band), angle = f("angle", d.angle), direction = j.optString("direction", d.direction),
                textEffect = j.optString("textEffect", d.textEffect), textDuration = j.optInt("textDuration", d.textDuration).let { if (j.optInt("motionVersion") < 2 && it == 240) 600 else it }, feather = f("feather", d.feather), textDirection = j.optString("textDirection", d.textDirection),
                widthDuration = j.optInt("widthDuration", d.widthDuration).let { if (j.optInt("motionVersion") < 2 && it == 360) 560 else it }, widthCurve = j.optString("widthCurve", d.widthCurve), stiffness = f("stiffness", d.stiffness), damping = f("damping", d.damping),
                appearanceDuration = j.optInt("appearanceDuration", d.appearanceDuration), entrance = j.optString("entrance", d.entrance), travel = f("travel", d.travel), startScale = f("startScale", d.startScale),
                shadowBlur = f("shadowBlur", d.shadowBlur), shadowAlpha = f("shadowAlpha", d.shadowAlpha), shadowOffset = f("shadowOffset", d.shadowOffset), shadowColor = j.optString("shadowColor", d.shadowColor)).normalized()
        }
        fun preset(style: String) = when (style) {
            "mercury" -> PillDesign(style = style, lightA = "#738096", lightB = "#FFFFFF", lightC = "#A6B6CD", period = 3400, strength = .8f)
            "interference" -> PillDesign(style = style, lightA = "#7C8BAA", lightB = "#C7BFE8", lightC = "#A7D6D6", period = 4200, strength = .85f)
            "cosmos" -> PillDesign(style = style, lightA = "#7784DE", lightB = "#C087C9", lightC = "#5DC8BB", period = 5600, strength = .9f)

            "aurora" -> PillDesign(style = style, lightA = "#738DDD", lightB = "#996CCB", lightC = "#4EBDAA", period = 3600, strength = .8f)
            "prism" -> PillDesign(style = style, lightA = "#5A8DEE", lightB = "#9D73CE", lightC = "#E3AA70", period = 2800, strength = .8f)
            "ember" -> PillDesign(style = style, lightA = "#BD9B63", lightB = "#FFFFFF", lightC = "#D3B688", period = 2800)
            "mist" -> PillDesign(style = style, band = 1.2f, angle = 0f, period = 3000)
            "scan" -> PillDesign(style = style, band = .32f, angle = -8f)
            "silk" -> PillDesign(style = style, band = .48f, period = 2400)
            else -> PillDesign(style = StatusEffects.normalize(style))
        }
    }
}
