package app.chocola.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos

/** Drawn at the window edges, with no dependency on controls, content or IME height. */
@Composable fun ScreenEdgeVeils(design: app.chocola.data.ChatBackground = app.chocola.data.ChatBackground()) {
    val edgeColor = wallpaperEdge(design)
    Canvas(Modifier.fillMaxSize()) {
        val depth = minOf(design.edgeDepth.dp.toPx(), size.height / 3)
        val white = edgeColor.copy(alpha = design.edgeOpacity)
        drawRect(Brush.verticalGradient(0f to white, .20f to white.copy(alpha = .98f * design.edgeOpacity), .54f to white.copy(alpha = .78f * design.edgeOpacity), 1f to white.copy(alpha = 0f), endY = depth), size = Size(size.width, depth))
        drawRect(Brush.verticalGradient(0f to white.copy(alpha = 0f), .46f to white.copy(alpha = .78f * design.edgeOpacity), .80f to white.copy(alpha = .98f * design.edgeOpacity), 1f to white,
            startY = size.height - depth, endY = size.height), topLeft = Offset(0f, size.height - depth), size = Size(size.width, depth))
    }
}

/** Save completion across lazy disposal, so scrolling back does not replay arrivals. */
@Composable fun MessageArrival(id: String, animate: Boolean, user: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var appeared by rememberSaveable(id) { mutableStateOf(false) }
    val progress = remember(id) { Animatable(if (animate && !appeared) 0f else 1f) }
    val logs = LocalDiagnostics.current
    LaunchedEffect(id) {
        appeared = true
        if (progress.value < 1f) logs.traceMotion("message_arrival", mapOf("message" to id, "user" to user, "configuredMs" to Motion.Close)) {
            progress.animateTo(1f, tween(Motion.Close, easing = Motion.Travel))
        }
    }
    Box(modifier.graphicsLayer {
        val p = progress.value
        alpha = (.18f + .82f * p).coerceIn(0f, 1f)
        translationY = (if (user) 10.dp else 0.dp).toPx() * (1 - p)
        if (user) {
            scaleX = .985f + .015f * p; scaleY = .985f + .015f * p
            transformOrigin = TransformOrigin(1f, 1f)
        }
    }) { content() }
}

@Composable fun ReplyActivity() {
    val animation = rememberInfiniteTransition(label = "replyActivity")
    val phase by animation.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "replyPulse")
    Canvas(Modifier.size(34.dp, 25.dp)) {
        repeat(3) { index ->
            val pulse = ((cos(phase - index * .8f) + 1) / 2).coerceIn(0f, 1f)
            drawCircle(Palette.Ink.copy(alpha = .30f + .55f * pulse), 2.6.dp.toPx(), Offset((4 + index * 11).dp.toPx(), size.height / 2 - 2.dp.toPx() * pulse))
        }
    }
}
