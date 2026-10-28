package app.chocola.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.chocola.data.Access
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object AccessMenuBounds {
    fun target(anchor: Rect, viewport: Rect, width: Float, height: Float, margin: Float): Rect {
        val w = minOf(width, (viewport.width - margin * 2).coerceAtLeast(1f))
        val lower = viewport.top + margin + 1f
        val bottom = anchor.bottom.coerceIn(lower, maxOf(lower, viewport.bottom - margin))
        val h = minOf(height, (bottom - viewport.top - margin).coerceAtLeast(1f))
        val left = anchor.left.coerceIn(viewport.left + margin, (viewport.right - margin - w).coerceAtLeast(viewport.left + margin))
        return Rect(left, bottom - h, left + w, bottom)
    }
    fun between(start: Rect, end: Rect, fraction: Float): Rect {
        val p = fraction.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * p
        return Rect(mix(start.left, end.left), mix(start.top, end.top), mix(start.right, end.right), mix(start.bottom, end.bottom))
    }
}

/** A single in-window surface grows out of the permission capsule; its text never scales. */
@Composable internal fun AccessMenu(anchor: Rect, selected: Access, onDismiss: () -> Unit, onSelect: (Access) -> Unit) {
    val motion = rememberMenuMotion(onDismiss)
    val progress = motion.bounds
    val select by rememberUpdatedState(onSelect)
    fun close(after: (() -> Unit)? = null) { if (after == null) motion.close() else motion.close.after(after) }
    BackHandler { close() }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = "会话权限" }
        .drawBehind { drawRect(Color.Black.copy(alpha = .1f * progress.value)) }
        .clickable(remember { MutableInteractionSource() }, indication = null) { close() }) {
        val density = LocalDensity.current
        val target = with(density) {
            val viewport = Rect(0f, WindowInsets.statusBars.getTop(this).toFloat(), maxWidth.toPx(), maxHeight.toPx() - WindowInsets.navigationBars.getBottom(this))
            AccessMenuBounds.target(anchor, viewport, 336.dp.toPx(), (348 * maxOf(1f, fontScale * LocalAppFontScale.current)).dp.toPx(), 14.dp.toPx())
        }
        MorphPanelSurface(anchor, target, motion, source = { Txt(selected.label, size = 12, weight = FontWeight.Medium, maxLines = 1) }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                items(Access.entries, key = { it.name }) { access ->
                    Press(Modifier.fillMaxWidth().heightIn(min = 65.dp), radius = 12.dp, onClick = { close { select(access) } }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Txt(access.label, size = 14, weight = FontWeight.Medium)
                                Txt(accessDescription(access), size = 12, color = Palette.Secondary, lineHeight = 18)
                            }
                            if (access == selected) Glyph("check", Modifier.size(18.dp))
                            else Spacer(Modifier.width(18.dp))
                        }
                    }
                }
            }
        }
    }
}
