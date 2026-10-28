package app.chocola.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chocola.data.PillDesign
import kotlinx.coroutines.channels.Channel
import androidx.compose.animation.core.CubicBezierEasing

/** Complete each transition, coalescing quick tool updates instead of repeatedly restarting it. */
@Composable internal fun StatusLabel(text: String, design: PillDesign, style: TextStyle, phase: State<Float>) {
    var displayed by remember { mutableStateOf(text) }
    val opacity = remember { Animatable(1f) }
    val latest by rememberUpdatedState(text)
    val updates = remember { Channel<String>(Channel.CONFLATED) }
    LaunchedEffect(text) { updates.send(text) }
    LaunchedEffect(design.textEffect, design.textDuration) {
        opacity.snapTo(1f)
        for (next in updates) {
            if (displayed == next) continue
            if (design.textEffect == "still") displayed = latest else {
                opacity.animateTo(0f, tween(design.textDuration / 3, easing = CubicBezierEasing(.4f, 0f, .7f, 1f)))
                displayed = updates.tryReceive().getOrNull() ?: latest
                opacity.animateTo(1f, tween(design.textDuration * 2 / 3, easing = CubicBezierEasing(.25f, 0f, .25f, 1f)))
            }
        }
    }
    BasicText(displayed, Modifier.graphicsLayer {
        translationY = if (design.textEffect == "glide") (1 - opacity.value) * 3.dp.toPx() else 0f
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithCache {
        val feather = design.feather.dp.toPx().coerceAtLeast(1f)
        val mask = Brush.horizontalGradient(listOf(Color.White, Color.Transparent), endX = feather)
        val beamWidth = (size.width * .45f).coerceAtLeast(1f)
        val beam = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .42f * design.strength), Color.Transparent), endX = beamWidth)
        onDrawWithContent {
            drawContent()
            if (design.style != "still") {
                val p = if (design.direction == "reverse") 1 - phase.value else phase.value
                translate(left = -beamWidth + p * (size.width + beamWidth * 2)) {
                    drawRect(beam, size = Size(beamWidth, size.height), blendMode = BlendMode.SrcAtop)
                }
            }
            val p = opacity.value
            if (p < .999f) withTransform({ if (design.textDirection == "reverse") scale(-1f, 1f, center) }) {
                val boundary = (size.width + feather) * p - feather
                val clear = (boundary + feather).coerceAtLeast(0f)
                drawRect(Color.Transparent, Offset(clear, 0f), Size((size.width - clear).coerceAtLeast(0f), size.height), blendMode = BlendMode.DstIn)
                translate(left = boundary) { drawRect(mask, size = Size(feather, size.height), blendMode = BlendMode.DstIn) }
                drawRect(Color.White.copy(alpha = p), blendMode = BlendMode.DstIn)
            }
        }
    }, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
