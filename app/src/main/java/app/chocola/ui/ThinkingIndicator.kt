package app.chocola.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.delay

@Composable internal fun ThinkingIndicator(label: String = "", startedAt: Long = System.currentTimeMillis()) {
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, tween(340)) }
    val cycle = rememberInfiniteTransition(label = "assistantWaiting")
    val phase = cycle.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "waitingBreath")
    val ink = LocalTranscriptInk.current
    var elapsed by remember(startedAt) { mutableLongStateOf((System.currentTimeMillis() - startedAt).coerceAtLeast(0) / 1000) }
    LaunchedEffect(startedAt) { while (true) { delay(1000); elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0) / 1000 } }
    val status = label.ifBlank { if (elapsed >= 10) "等待回复" else "" }
    Row(Modifier.padding(start = 22.dp, top = 14.dp, bottom = 2.dp).heightIn(min = 28.dp)
        .semantics(mergeDescendants = true) { contentDescription = status.ifBlank { "等待回复" } }
        .graphicsLayer { alpha = arrival.value }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(30.dp, 28.dp)) {
            repeat(3) { i ->
                val wave = (.5f - .5f * cos(phase.value * 2 * PI - i * .65)).toFloat()
                drawCircle(ink.copy(alpha = .26f + wave * .52f), (2.4f + wave * .25f).dp.toPx(), Offset((5f + 9f * i).dp.toPx(), center.y))
            }
        }
        AnimatedContent(status, transitionSpec = {
            (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 }).togetherWith(fadeOut(tween(100)))
        }, label = "replyStage") { value -> if (value.isNotBlank()) Txt(value, size = 12, color = LocalTranscriptSecondary.current) }
        if (elapsed >= 10) Txt("${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}", size = 12, color = LocalTranscriptSecondary.current)
    }
}
