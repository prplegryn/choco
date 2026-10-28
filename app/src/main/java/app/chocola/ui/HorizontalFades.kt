package app.chocola.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** The remaining scroll distance controls opacity directly, without restarting animations. */
internal fun Modifier.horizontalFades(scroll: ScrollState, paper: Color): Modifier = drawWithCache {
    val width = minOf(26.dp.toPx(), size.width / 3).coerceAtLeast(1f)
    val left = Brush.horizontalGradient(listOf(paper, Color.Transparent), endX = width)
    val right = Brush.horizontalGradient(listOf(Color.Transparent, paper), startX = size.width - width, endX = size.width)
    onDrawWithContent {
        drawContent()
        if (scroll.maxValue != Int.MAX_VALUE && scroll.maxValue > 0) {
            val leftAlpha = (scroll.value / width).coerceIn(0f, 1f)
            val rightAlpha = ((scroll.maxValue - scroll.value) / width).coerceIn(0f, 1f)
            if (leftAlpha > 0f) drawRect(left, size = Size(width, size.height), alpha = leftAlpha)
            if (rightAlpha > 0f) drawRect(right, topLeft = Offset(size.width - width, 0f), size = Size(width, size.height), alpha = rightAlpha)
        }
    }
}

/** Alpha masks preserve wallpapers and image corners instead of painting white over the strip. */
internal fun Modifier.horizontalImageFades(scroll: ScrollState): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    val edge = minOf(22.dp.toPx(), size.width / 4).coerceAtLeast(1f)
    onDrawWithContent {
        drawContent()
        if (scroll.maxValue != Int.MAX_VALUE && scroll.maxValue > 0) {
            val left = (scroll.value / edge).coerceIn(0f, 1f)
            val right = ((scroll.maxValue - scroll.value) / edge).coerceIn(0f, 1f)
            if (left > 0f) drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 1 - left), Color.Black), endX = edge),
                size = Size(edge, size.height), blendMode = BlendMode.DstIn)
            if (right > 0f) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Black.copy(alpha = 1 - right)), startX = size.width - edge, endX = size.width),
                topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height), blendMode = BlendMode.DstIn)
        }
    }
}
