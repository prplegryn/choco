package app.chocola.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import app.chocola.ChocolaApp
import app.chocola.data.FrameMonitor

@Composable private fun DialogBars() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        var frames: FrameMonitor? = null
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setWindowAnimations(0)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.decorView.isForceDarkAllowed = false
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
            (context.applicationContext as? ChocolaApp)?.repository?.logs?.let { logs ->
                frames = FrameMonitor(window, logs, "sheet").also { it.start() }
            }
        }
        onDispose { frames?.close() }
    }
}

@Composable fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(OverlayClose) -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val position = remember { Animatable(1f) }
    val contentInk = remember { Animatable(0f) }
    val parentFocus = LocalFocusManager.current
    val sequence = remember { DismissSequence() }
    val logs = LocalDiagnostics.current
    var closing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var height by remember { mutableFloatStateOf(0f) }
    var travel by remember { mutableFloatStateOf(screenHeight) }
    val close = remember {
        OverlayClose { after ->
            if (sequence.begin(after)) closing = true
        }
    }
    LaunchedEffect(closing) {
        parentFocus.clearFocus(force = true)
        snapshotFlow { height }.first { it > 0f }
        logs.traceMotion("sheet", mapOf("direction" to if (closing) "exit" else "enter", "heightPx" to height,
            "configuredMs" to if (closing) Motion.Close else Motion.Overlay)) { if (closing) {
            travel = height.coerceAtLeast(1f)
            coroutineScope {
                launch { contentInk.animateTo(0f, tween(75)) }
                position.animateTo(1f, tween(Motion.Close, easing = Motion.Travel))
            }
            sequence.finish { dismiss() }
        } else {
            travel = height
            coroutineScope {
                launch { contentInk.animateTo(1f, tween(Motion.Overlay, delayMillis = RevealMotion.Lag, easing = Motion.Travel)) }
                position.animateTo(0f, tween(Motion.Overlay, easing = Motion.Travel))
            }
        } }
    }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogBars()
        val focus = LocalFocusManager.current
        DisposableEffect(Unit) { onDispose { focus.clearFocus(force = true) } }
        BackHandler { close() }
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = .18f * (1 - position.value))) }
            .clickable(remember { MutableInteractionSource() }, null, onClick = close), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .88f).dp)
                .onSizeChanged { height = it.height.toFloat() }
                .graphicsLayer { translationY = travel * position.value }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color.White)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Border))
                Column(Modifier.fillMaxWidth().menuInk { contentInk.value }) { content(close) }
            }
        }
    }
}

@Composable fun DrawerLayer(onDismiss: () -> Unit, content: @Composable BoxScope.(OverlayClose) -> Unit) {
    val logs = LocalDiagnostics.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun leaveSearch() { focus.clearFocus(force = true); keyboard?.hide() }
    val dismiss by rememberUpdatedState(onDismiss)
    val position = remember { Animatable(1f) }
    val sequence = remember { DismissSequence() }
    var width by remember { mutableFloatStateOf(1f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf<Job?>(null) }
    fun settle(target: Float, finish: Boolean = false) {
        motion?.cancel()
        motion = scope.launch {
            logs.traceMotion("drawer", mapOf("direction" to if (finish) "exit" else "enter", "widthPx" to width,
                "configuredMs" to if (finish) Motion.Close else Motion.Overlay)) {
            if (dragging) { position.snapTo(drag); dragging = false }
            position.animateTo(target, tween(if (finish) Motion.Close else Motion.Overlay, easing = Motion.Travel))
            if (finish) sequence.finish { dismiss() }
            }
        }
    }
    val close = OverlayClose { after -> if (sequence.begin(after)) { leaveSearch(); settle(1f, finish = true) } }.apply {
        parallel = { action -> if (sequence.begin()) { leaveSearch(); action(); settle(1f, finish = true) } }
    }
    LaunchedEffect(Unit) { settle(0f) }
    PredictiveBackHandler(enabled = !sequence.closing) { events ->
        motion?.cancel()
        try {
            events.collect { position.snapTo(it.progress) }
            close()
        } catch (_: CancellationException) { settle(0f) }
    }
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = .18f * (1 - if (dragging) drag else position.value))) }
        .clickable(remember { MutableInteractionSource() }, null, onClick = close)) {
        Box(Modifier.width((LocalConfiguration.current.screenWidthDp * .86f).coerceAtMost(380f).dp).fillMaxHeight()
            .onSizeChanged { width = it.width.toFloat() }
            .graphicsLayer { translationX = -size.width * (if (dragging) drag else position.value) }
            .clip(RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)).background(Color(0xFFFAFAFB))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { motion?.cancel(); drag = position.value; dragging = true },
                    onDragEnd = { if (drag > .22f) close() else settle(0f) },
                    onDragCancel = { settle(0f) }
                ) { change, amount ->
                    if (!sequence.closing) {
                        change.consume()
                        drag = (drag - amount / width).coerceIn(0f, 1f)
                    }
                }
            }
            .clickable(remember { MutableInteractionSource() }, null) {}
            ) {
            content(close)
        }
    }
}

@Composable fun ConfirmSheet(text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Sheet(onDismiss) { close ->
        Txt(text, Modifier.padding(top = 6.dp, bottom = 20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("取消", Modifier.weight(1f), solid = false, onClick = close)
            ActionButton(action, Modifier.weight(1f)) { close.after(onConfirm) }
        }
    }
}

@Composable fun ChoiceSheet(items: List<String>, selected: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    Sheet(onDismiss) { close ->
        items.forEach { item ->
            Press(Modifier.fillMaxWidth().height(52.dp), onClick = { close.after { onSelect(item) } }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(item, Modifier.weight(1f)); if (selected == item) Glyph("check", Modifier.size(19.dp))
                }
            }
        }
    }
}
