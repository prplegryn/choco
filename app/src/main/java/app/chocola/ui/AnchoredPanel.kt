package app.chocola.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class PanelGrowth { MENU, TASKS, CENTERED, CONTEXT }
internal object PanelBounds {
    fun target(anchor: Rect, viewport: Rect, width: Float, height: Float, margin: Float, growth: PanelGrowth, lip: Float): Rect {
        val leftEdge = viewport.left + margin
        val rightEdge = maxOf(leftEdge + 1f, viewport.right - margin)
        val topEdge = viewport.top + if (growth == PanelGrowth.CENTERED) margin else 0f
        val bottomEdge = maxOf(topEdge + 1f, viewport.bottom - margin)
        val w = minOf(width, rightEdge - leftEdge)
        if (growth == PanelGrowth.CONTEXT) {
            val h = minOf(height, bottomEdge - topEdge)
            val left = (anchor.center.x - w / 2).coerceIn(leftEdge, maxOf(leftEdge, rightEdge - w))
            val top = (if (anchor.bottom + lip + h <= bottomEdge) anchor.bottom + lip else anchor.top - lip - h).coerceIn(topEdge, maxOf(topEdge, bottomEdge - h))
            return Rect(left, top, left + w, top + h)
        }
        if (growth == PanelGrowth.CENTERED) {
            val bottom = (anchor.bottom + lip).coerceIn(topEdge + 1f, bottomEdge)
            val h = minOf(height, bottom - topEdge)
            val left = (anchor.center.x - w / 2).coerceIn(leftEdge, maxOf(leftEdge, rightEdge - w))
            return Rect(left, bottom - h, left + w, bottom)
        }
        val right = (anchor.right + if (growth == PanelGrowth.MENU) lip else 0f).coerceIn(leftEdge + w, rightEdge)
        val top = (anchor.top - if (growth == PanelGrowth.MENU) lip else 0f).coerceIn(topEdge, bottomEdge - 1f)
        return Rect(right - w, top, right, top + minOf(height, bottomEdge - top))
    }
}

/** One floating surface changes its bounds; content retains normal-sized text and touch targets. */
@Composable internal fun AnchoredPanel(anchor: Rect, title: String, width: Int, height: Int, growth: PanelGrowth,
    onDismiss: () -> Unit, source: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.(OverlayClose) -> Unit) {
    val motion = rememberMenuMotion(onDismiss)
    BackHandler { motion.close() }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = title }
        .drawBehind { drawRect(Color.Black.copy(alpha = .07f * motion.bounds.value)) }
        .clickable(remember { MutableInteractionSource() }, indication = null) { motion.close() }) {
        val density = LocalDensity.current
        val target = with(density) {
            val viewport = Rect(0f, WindowInsets.statusBars.getTop(this).toFloat(), maxWidth.toPx(), maxHeight.toPx() - WindowInsets.navigationBars.getBottom(this))
            PanelBounds.target(anchor, viewport, width.dp.toPx(), (height * maxOf(1f, fontScale * LocalAppFontScale.current)).dp.toPx(), 12.dp.toPx(), growth, 5.dp.toPx())
        }
        MorphPanelSurface(anchor, target, motion, source) { content(motion.close) }
    }
}

/** Surface, normal-sized content, and the original button are separate layers in one moving frame. */
@Composable internal fun MorphPanelSurface(anchor: Rect, target: Rect, motion: MenuMotion,
    source: (@Composable () -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val p = motion.bounds.value
    val bounds = AccessMenuBounds.between(anchor, target, p)
    // RoundedCornerShape naturally clamps to half the frame height near the source.
    val radius = 24.dp
    val shape = RoundedCornerShape(radius)
    val width = bounds.width.roundToInt().coerceAtLeast(1)
    val height = bounds.height.roundToInt().coerceAtLeast(1)
    Layout(content = {
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = if (source == null) 1f else (p / .38f).coerceIn(0f, 1f)
        }.floatingSurface(radius))
        Layout(content = { Column(Modifier.fillMaxSize().panelInk(anchor, target, { motion.bounds.value }, { motion.ink.value }), content = content) },
            modifier = Modifier.clip(shape)) { children, _ ->
            val child = children.single().measure(Constraints.fixed(target.width.roundToInt().coerceAtLeast(1), target.height.roundToInt().coerceAtLeast(1)))
            layout(width, height) { child.place((target.left - bounds.left).roundToInt(), (target.top - bounds.top).roundToInt()) }
        }
        Box(Modifier.graphicsLayer { alpha = (1f - p / .38f).coerceIn(0f, 1f) }, contentAlignment = androidx.compose.ui.Alignment.Center) { source?.invoke() }
    }, modifier = Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
        .clickable(remember { MutableInteractionSource() }, indication = null) {}) { children, _ ->
        val frame = Constraints.fixed(width, height)
        val surface = children[0].measure(frame)
        val body = children[1].measure(frame)
        val button = children[2].measure(Constraints.fixed(anchor.width.roundToInt().coerceAtLeast(1), anchor.height.roundToInt().coerceAtLeast(1)))
        layout(width, height) {
            surface.place(0, 0); body.place(0, 0)
            button.place((anchor.left - bounds.left).roundToInt(), (anchor.top - bounds.top).roundToInt())
        }
    }
}
