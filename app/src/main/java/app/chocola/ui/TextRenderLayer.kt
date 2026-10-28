package app.chocola.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext

/** Native text retains selection, links and accessibility; only appended ink is animated. */
@Composable internal fun RenderText(content: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle,
    animate: Boolean = false, maxLines: Int = Int.MAX_VALUE, animateInitial: Boolean = animate, inlineContent: Map<String, InlineTextContent> = emptyMap()) {
    var seen by rememberSaveable { mutableStateOf(false) }
    val reveal = remember { TextReveal(if (animate && animateInitial && !seen) "" else content.text) }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    var generation by remember { mutableIntStateOf(0) }
    var frame by remember { mutableLongStateOf(System.nanoTime()) }
    var duration by remember { mutableLongStateOf(180_000_000L) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    SideEffect {
        seen = true
        if (reveal.append(content.text, animate, System.nanoTime())) { generation++; wake.trySend(Unit) }
    }
    LaunchedEffect(reveal) {
        val scale = currentCoroutineContext()[MotionDurationScale]
        for (ignored in wake) {
            while (reveal.active()) withFrameNanos { now ->
                duration = (180_000_000L * (scale?.scaleFactor ?: 1f)).toLong()
                frame = now
                if (reveal.advance(now, duration)) generation++
            }
        }
    }
    BasicText(content, modifier.drawWithCache {
        generation
        val result = layout?.takeIf { it.layoutInput.text == content }
        val ranges = if (result == null) emptyList() else reveal.batches.filter { it.end <= content.length }.map { it to result.getPathForRange(it.start, it.end) }
        val combined = Path().apply { ranges.forEach { addPath(it.second) } }
        val paint = Paint()
        onDrawWithContent {
            if (ranges.isEmpty()) drawContent()
            else {
                clipPath(combined, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                for ((batch, path) in ranges) clipPath(path) {
                    paint.alpha = TextReveal.opacity(batch, frame, duration)
                    drawContext.canvas.saveLayer(path.getBounds(), paint)
                    this@onDrawWithContent.drawContent()
                    drawContext.canvas.restore()
                }
            }
        }
    }, style = style, maxLines = maxLines, inlineContent = inlineContent, onTextLayout = { layout = it })
}
