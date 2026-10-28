package app.chocola.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/** The arrow retracts as the stop square settles into the same optical centre. */
@Composable internal fun SendGlyph(running: Boolean, enabled: Boolean, inserting: Boolean = false) {
    val progress = animateFloatAsState(if (running) 1f else 0f, tween(210, easing = Motion.Travel), label = "sendSymbol")
    val insertion = animateFloatAsState(if (inserting) 1f else 0f, tween(270, easing = Motion.Travel), label = "insertSymbol")
    Box(Modifier.size(22.dp).drawWithCache {
        val unit = size.width / 24
        val arrow = Path()
        val stroke = Stroke(2.15f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            val p = progress.value
            val i = insertion.value
            fun v(start: Float, end: Float) = (start + (end - start) * i) * unit
            arrow.reset()
            arrow.moveTo(v(6f, 11f), v(11f, 8f)); arrow.lineTo(v(12f, 7f), v(5f, 12f)); arrow.lineTo(v(18f, 11f), v(11f, 16f))
            arrow.moveTo(v(12f, 7f), v(5f, 12f)); arrow.lineTo(v(12f, 19f), 12 * unit); arrow.lineTo(v(12f, 19f), v(19f, 5f))
            val ink = Color.White.copy(alpha = if (enabled) 1f else .4f)
            translate(top = 2 * unit * p) {
                scale(1 - .2f * p) { drawPath(arrow, ink, alpha = 1 - p, style = stroke) }
            }
            drawLine(ink.copy(alpha = ink.alpha * i * (1 - p)), Offset(5 * unit, 20 * unit), Offset(19 * unit, 20 * unit), 2.15f * unit, StrokeCap.Round)
            val side = (9.5f + 1.5f * p) * unit
            drawRoundRect(ink, topLeft = Offset((size.width - side) / 2, (size.height - side) / 2), size = Size(side, side), cornerRadius = CornerRadius(2 * unit), alpha = p)
        }
    })
}

@Composable internal fun CopyGlyph(copied: Boolean) {
    val progress = animateFloatAsState(if (copied) 1f else 0f, tween(190, easing = Motion.Travel), label = "copySymbol")
    Box(Modifier.size(18.dp).drawWithCache {
        val u = size.width / 24
        val back = Path().apply {
            moveTo(6 * u, 16 * u); lineTo(5 * u, 16 * u); quadraticBezierTo(3 * u, 16 * u, 3 * u, 14 * u)
            lineTo(3 * u, 5 * u); quadraticBezierTo(3 * u, 3 * u, 5 * u, 3 * u)
            lineTo(14 * u, 3 * u); quadraticBezierTo(16 * u, 3 * u, 16 * u, 5 * u)
        }
        val check = Path().apply { moveTo(4 * u, 12 * u); lineTo(9.5f * u, 17.5f * u); lineTo(20 * u, 6.5f * u) }
        val stroke = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            val p = progress.value
            drawPath(back, Palette.Secondary, alpha = 1 - p, style = stroke)
            drawRoundRect(Palette.Secondary, Offset(8 * u, 8 * u), Size(13 * u, 13 * u), CornerRadius(2.1f * u), alpha = 1 - p, style = stroke)
            clipRect(right = size.width * p) { drawPath(check, Palette.Green, alpha = p, style = stroke) }
        }
    })
}
