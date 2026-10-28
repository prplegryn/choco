package app.chocola.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import app.chocola.data.PillDesign
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun pillColor(value: String) = Color(android.graphics.Color.parseColor(value))
private fun Color.light(opacity: Float) = copy(alpha = alpha * opacity)

/** Only the selected effect allocates brushes; phase reads remain entirely in drawing. */
@Composable internal fun statusSurface(design: PillDesign, phase: State<Float>): Modifier {
    if (design.style == "still" || design.strength <= 0f) return Modifier
    return Modifier.drawWithCache {
        if (size.width <= 0f || size.height <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
        val strength = design.strength
        val style = design.style
        val capsule = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(minOf(design.corner.dp.toPx(), size.height / 2)))) }
        val bandWidth = size.width * design.band
        val bandHeight = maxOf(size.height * 6, size.width * 2)
        val a = pillColor(design.lightA)
        val b = pillColor(design.lightB)
        val c = pillColor(design.lightC)
        val primary = if (style !in setOf("aurora", "halo", "breath")) Brush.horizontalGradient(when (style) {
            "prism" -> listOf(Color.Transparent, a.light(.24f * strength), b.light(.22f * strength), Color.White.light(.3f * strength), c.light(.2f * strength), Color.Transparent)
            "mist", "tide" -> listOf(Color.Transparent, a.light(.12f * strength), b.light(.24f * strength), Color.Transparent)
            else -> listOf(Color.Transparent, a.light(.17f * strength), b.light(.34f * strength), c.light(.1f * strength), Color.Transparent)
        }, endX = bandWidth) else null
        val breath = if (style in setOf("breath", "aurora")) Brush.radialGradient(listOf(b.light(.24f * strength),
            a.light(.09f * strength), c.light(.03f * strength), Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = size.width * .7f * design.band / .72f) else null
        val radius = size.width * .63f * design.band / .72f
        val aurora = if (style in setOf("aurora", "mercury", "interference", "cosmos")) listOf(a, b, c).map { color ->
            Brush.radialGradient(listOf(color.light(.24f * strength), color.light(.08f * strength), Color.Transparent), center = Offset.Zero, radius = radius)
        } else emptyList()
        val haloWidth = (design.band / .72f).coerceIn(.25f, 2f)
        val halo = if (style == "halo") Brush.sweepGradient(0f to Color.Transparent, .10f * haloWidth to a.light(.14f * strength), .22f * haloWidth to b.light(.32f * strength),
            .35f * haloWidth to Color.Transparent, .45f * haloWidth to c.light(.12f * strength), 1f to Color.Transparent, center = Offset(size.width / 2, size.height / 2)) else null
        val glaze = if (style == "glass") Brush.verticalGradient(listOf(b.light(.24f * strength), Color.Transparent, a.light(.06f * strength))) else null
        onDrawWithContent {
            drawContent()
            val p = when (design.direction) { "reverse" -> 1 - phase.value; "alternate" -> (.5 - .5 * cos(phase.value * 2 * PI)).toFloat(); else -> phase.value }
            val pulse = (.5f - .5f * cos(p * 2 * PI)).toFloat()
            clipPath(capsule) {
                when (style) {
                    "breath" -> {
                        drawRect(a, alpha = strength * .045f * pulse)
                        drawRect(requireNotNull(breath), alpha = .25f + .75f * pulse)
                    }
                    "tide" -> {
                        val crest = size.width * (.5f + .55f * sin(p * 2 * PI).toFloat()) - bandWidth / 2
                        translate(left = crest, top = (size.height - bandHeight) / 2) {
                            rotate(design.angle, pivot = Offset(bandWidth / 2, bandHeight / 2)) {
                                drawRect(requireNotNull(primary), size = Size(bandWidth, bandHeight), alpha = .5f + .5f * pulse)
                            }
                        }
                    }
                    "mercury", "interference", "cosmos" -> {
                        val turns = p * 2 * PI
                        repeat(if (style == "cosmos") 4 else 3) { index ->
                            val offset = turns + index * 2.094
                            val x = size.width * (.5f + .48f * sin(offset).toFloat())
                            val y = size.height * (.5f + .85f * cos(offset * if (style == "interference") 2 else 1).toFloat())
                            translate(x, y) { drawCircle(aurora[index % 3], radius, Offset.Zero, alpha = if (style == "mercury") .72f else 1f) }
                        }
                        val travel = size.width + bandWidth * 2
                        translate(left = -bandWidth + travel * p, top = (size.height - bandHeight) / 2) {
                            rotate(design.angle + 14f, pivot = Offset(bandWidth / 2, bandHeight / 2)) {
                                drawRect(requireNotNull(primary), size = Size(bandWidth, bandHeight), alpha = .72f)
                            }
                        }
                        if (style != "mercury") translate(left = size.width - travel * p, top = (size.height - bandHeight) / 2) {
                            rotate(-design.angle - 24f, pivot = Offset(bandWidth / 2, bandHeight / 2)) {
                                drawRect(requireNotNull(primary), size = Size(bandWidth, bandHeight), alpha = .48f + pulse * .25f)
                            }
                        }
                    }
                    "aurora" -> {
                        repeat(3) { index ->
                            val angle = (p * 2 * PI + index * 2.094 + design.angle * PI / 180).toFloat()
                            val x = size.width * (.5f + .35f * sin(angle))
                            val y = size.height * (.5f + .7f * cos(angle))
                            translate(x, y) { drawCircle(aurora[index], radius, Offset.Zero) }
                        }
                        drawRect(requireNotNull(breath), alpha = .25f + .35f * pulse)
                    }
                    "halo" -> rotate(p * 360f + design.angle) {
                        drawRect(requireNotNull(halo), topLeft = Offset(-size.width, -size.width), size = Size(size.width * 3, size.width * 3))
                    }
                    else -> {
                        val travel = size.width + bandWidth + size.height * 2
                        val x = -bandWidth - size.height + travel * p
                        translate(left = x, top = (size.height - bandHeight) / 2) {
                            rotate(design.angle, pivot = Offset(bandWidth / 2, bandHeight / 2)) { drawRect(requireNotNull(primary), size = Size(bandWidth, bandHeight)) }
                        }
                        if (style == "ripple" || style == "silk") {
                            val second = if (style == "ripple") size.width + size.height - travel * p else x - bandWidth * 1.1f
                            translate(left = second, top = (size.height - bandHeight) / 2) {
                                rotate(if (style == "ripple") -design.angle else design.angle, pivot = Offset(bandWidth / 2, bandHeight / 2)) {
                                    drawRect(requireNotNull(primary), size = Size(bandWidth, bandHeight), alpha = .55f)
                                }
                            }
                        }
                        if (glaze != null) drawRect(glaze, alpha = .5f + .5f * pulse)
                    }
                }
            }
        }
    }
}
