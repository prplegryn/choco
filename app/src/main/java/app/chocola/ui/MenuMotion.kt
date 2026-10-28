package app.chocola.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class MenuMotion(val bounds: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val ink: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, val close: OverlayClose)

@Composable internal fun rememberMenuMotion(onDismiss: () -> Unit): MenuMotion {
    val bounds = remember { Animatable(0f) }
    val ink = remember { Animatable(0f) }
    val sequence = remember { DismissSequence() }
    val logs = LocalDiagnostics.current
    var closing by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val close = OverlayClose { after -> if (sequence.begin(after)) closing = true }
    LaunchedEffect(closing) {
        logs.traceMotion("anchored_menu", mapOf("direction" to if (closing) "exit" else "enter", "configuredMs" to if (closing) Motion.Close else 300)) { if (closing) {
            coroutineScope {
                launch { ink.animateTo(0f, tween(100)) }
                bounds.animateTo(0f, tween(Motion.Close, easing = Motion.Travel))
            }
            sequence.finish { dismiss() }
        } else {
            coroutineScope {
                launch { ink.animateTo(1f, tween(110, delayMillis = RevealMotion.Lag)) }
                bounds.animateTo(1f, tween(300, easing = Motion.Travel))
            }
        } }
    }
    return remember(bounds, ink, close) { MenuMotion(bounds, ink, close) }
}

internal object RevealMotion {
    const val Lag = 12
    fun following(progress: Float): Float = ((progress - .025f) / .975f).coerceIn(0f, 1f)
}

/** Fixed-size text follows a downward expansion, with a soft edge instead of a second entrance. */
internal fun Modifier.menuInk(progress: () -> Float): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val feather = minOf(28.dp.toPx(), size.height * .22f).coerceAtLeast(1f)
        onDrawWithContent {
            val p = progress().coerceIn(0f, 1f)
            if (p > .001f) {
                drawContent()
                if (p < .999f) {
                    val edge = size.height * p
                    val soft = feather * (1f - p)
                    drawRect(Brush.verticalGradient(listOf(Color.White, Color.Transparent), startY = edge - soft, endY = edge.coerceAtLeast(.01f)), blendMode = BlendMode.DstIn)
                }
            }
        }
    }

/** The mask tracks all moving edges, so a lower-left source reveals upward and to the right. */
internal fun Modifier.panelInk(anchor: Rect, target: Rect, progress: () -> Float, opacity: () -> Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        val feather = 20.dp.toPx()
        onDrawWithContent {
            val p = RevealMotion.following(progress())
            val alpha = opacity().coerceIn(0f, 1f)
            if (p > .001f && alpha > .001f) {
                drawContent()
                if (p < .999f) {
                    val frame = AccessMenuBounds.between(anchor, target, p)
                    val soft = (feather * (1f - p)).coerceAtLeast(.01f)
                    val left = frame.left - target.left
                    val right = frame.right - target.left
                    val top = frame.top - target.top
                    val bottom = frame.bottom - target.top
                    if (left > 0f) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White), startX = left, endX = left + soft), blendMode = BlendMode.DstIn)
                    if (right < size.width) drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent), startX = right - soft, endX = right), blendMode = BlendMode.DstIn)
                    if (top > 0f) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = top, endY = top + soft), blendMode = BlendMode.DstIn)
                    if (bottom < size.height) drawRect(Brush.verticalGradient(listOf(Color.White, Color.Transparent), startY = bottom - soft, endY = bottom), blendMode = BlendMode.DstIn)
                }
                if (alpha < .999f) drawRect(Color.White.copy(alpha = alpha), blendMode = BlendMode.DstIn)
            }
        }
    }
