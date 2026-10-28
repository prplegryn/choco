package app.chocola.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import kotlin.math.*

@Composable internal fun ThinkingGauge(fraction: Float, modifier: Modifier = Modifier.size(22.dp)) {
    val value = animateFloatAsState(fraction, tween(220, easing = Motion.Travel), label = "thinkingNeedle")
    Canvas(modifier) { drawGauge(value.value, false, 0) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGauge(fraction: Float, large: Boolean, ticks: Int, marks: List<Float> = emptyList()) {
    val r = minOf(size.width * .42f, size.height * .70f)
    val c = Offset(size.width / 2, size.height * if (large) .80f else .67f)
    val line = if (large) 3.dp.toPx() else 1.8.dp.toPx()
    val arcSize = Size(r * 2, r * 2)
    drawArc(Palette.Border, 180f, 180f, false, c - Offset(r, r), arcSize, style = Stroke(line, cap = StrokeCap.Round))
    if (fraction > .005f) drawArc(Palette.Ink, 180f, fraction * 180, false, c - Offset(r, r), arcSize, style = Stroke(line, cap = StrokeCap.Round))
    if (large) repeat(ticks) { i ->
        val angle = PI * (1 + (marks.getOrNull(i) ?: (i.toFloat() / (ticks - 1).coerceAtLeast(1))))
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(Palette.Secondary, c + dir * (r - 12.dp.toPx()), c + dir * (r - 18.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
    }
    val a = PI * (1 + fraction.coerceIn(0f, 1f))
    val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
    val base = if (large) 3.5.dp.toPx() else 1.8.dp.toPx()
    // Rounded strokes stay antialiased at every angle and density, without a hard triangle tip.
    drawLine(Palette.Ink, c - dir * base, c + dir * r * .77f,
        if (large) 2.dp.toPx() else 1.5.dp.toPx(), StrokeCap.Round)
    drawCircle(Palette.Ink, base, c)
    if (large) drawCircle(androidx.compose.ui.graphics.Color.White, 1.25.dp.toPx(), c)

}

@Composable internal fun ThinkingMenu(anchor: Rect, provider: Provider, session: Session?, running: Boolean,
    models: List<Provider>, onDismiss: () -> Unit, onModel: (Provider) -> Unit, onSelect: (String) -> Unit) {
    val codex = CodexWire.isCodex(provider)
    var choosingModel by remember { mutableStateOf(false) }
    val options = remember(provider) { ThinkingControls.options(provider).filter { it.key.isNotBlank() } }
    var selected by remember(session?.id, provider.id, provider.model, provider.reasoningLevels) { mutableStateOf(ThinkingControls.selected(provider, session)) }
    val effective = ThinkingControls.effective(provider, session?.let { ThinkingControls.choose(it, provider, selected) })
    val resolved = when { effective.thinking == "disabled" -> "off"; effective.reasoningEffort.isNotBlank() -> effective.reasoningEffort; effective.thinking == "enabled" -> "on"; else -> ModelCatalog.profile(provider)?.effort.orEmpty() }
    val index = options.indexOfFirst { it.key == selected.ifBlank { resolved } }.coerceAtLeast(0)
    val displayedLabel = if (selected.isBlank()) {
        if (codex && options.any { it.key == resolved }) "模型默认（$resolved）" else "模型默认"
    } else ThinkingControls.label(provider, selected)
    val fraction by animateFloatAsState(ThinkingControls.fraction(provider, session?.let { ThinkingControls.choose(it, provider, selected) }), tween(180), label = "dialValue")
    val latestSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)
    val haptics = LocalHapticFeedback.current
    val marks = remember(provider, options) { options.map { level -> ThinkingControls.fraction(provider,
        Session(reasoningLevel = level.key, reasoningModel = ThinkingControls.modelKey(provider))) } }
    fun atFraction(f: Float, commit: Boolean = true) {
        if (marks.isNotEmpty()) chooseNearest(f, marks) { i ->
            val option = options[i]
            if (currentSelected != option.key) { selected = option.key; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); if (commit) latestSelect(option.key) }
        }
    }
    fun choose(i: Int) {
        if (options.isEmpty()) return
        val option = options[i.coerceIn(options.indices)]
        if (currentSelected != option.key) { selected = option.key; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); latestSelect(option.key) }
    }
    val height = (if (codex) 248 else 232) + if (running) 22 else 0
    AnchoredPanel(anchor, if (codex) "思考深度" else "思考程度", 304, height, PanelGrowth.CENTERED, onDismiss, source = { ThinkingGauge(ThinkingControls.fraction(provider, session), Modifier.size(24.dp)) }) { close ->
        AnimatedContent(choosingModel, Modifier.fillMaxSize(), transitionSpec = {
            (fadeIn(tween(230, delayMillis = 100)) + slideInVertically(tween(320, easing = Motion.Travel)) { it / 12 }) togetherWith
                fadeOut(tween(100))
        }, label = "dialModels") { showModels ->
        if (showModels) Column(Modifier.fillMaxSize().padding(8.dp)) {
            Press(Modifier.fillMaxWidth().height(40.dp), onClick = { choosingModel = false }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Glyph("back", Modifier.size(15.dp)); Txt("选择模型", size = 13, weight = FontWeight.Medium)
                }
            }
            ModelChoices(models, provider.id, Modifier.weight(1f)) { model -> onModel(model); choosingModel = false }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Press(Modifier.height(38.dp).widthIn(max = 252.dp), radius = 19.dp, description = "切换模型", onClick = { choosingModel = true }) {
                Box(Modifier.background(Palette.Fill, RoundedCornerShape(19.dp)).padding(horizontal = 15.dp, vertical = 9.dp)) {
                    Txt(provider.model, size = 13, weight = FontWeight.Medium, maxLines = 1)
                }
            }
            Canvas(Modifier.fillMaxWidth().height(if (codex) 100.dp else 125.dp)
                .semantics {
                    contentDescription = "思考程度，$displayedLabel"
                    progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..options.lastIndex.coerceAtLeast(0).toFloat(), (options.size - 2).coerceAtLeast(0))
                    if (options.size > 1) setProgress { choose(it.roundToInt()); true }
                }
                .pointerInput(options) { detectTapGestures { p ->
                    val a = atan2((size.height * .80f - p.y).coerceAtLeast(0f), p.x - size.width / 2f)
                    atFraction((1 - a / PI).toFloat())
                } }
                .pointerInput(options) { detectDragGestures(onDragEnd = { latestSelect(selected) }, onDragCancel = { latestSelect(selected) }, onDragStart = { p ->
                    val a = atan2((size.height * .80f - p.y).coerceAtLeast(0f), p.x - size.width / 2f)
                    atFraction((1 - a / PI).toFloat(), commit = false)
                }) { change, _ ->
                    change.consume()
                    val a = atan2((size.height * .80f - change.position.y).coerceAtLeast(0f), change.position.x - size.width / 2f)
                    atFraction((1 - a / PI).toFloat(), commit = false)
                } }) { drawGauge(fraction, true, options.size, marks) }
            if (codex) {
                Box(Modifier.fillMaxWidth().height(30.dp)) {
                    AnimatedContent(displayedLabel, Modifier.align(Alignment.Center), transitionSpec = {
                        fadeIn(tween(160, delayMillis = 40)) togetherWith fadeOut(tween(100))
                    }, label = "codexEffortLabel") { label -> Txt(label, size = 16, weight = FontWeight.Medium) }
                    if (selected.isNotBlank()) IconButton("undo", "恢复模型默认", Modifier.align(Alignment.CenterEnd).size(28.dp), floating = false) {
                        selected = ""; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); latestSelect("")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    options.forEachIndexed { i, level ->
                        val active = level.key == selected.ifBlank { resolved }
                        Press(Modifier.weight(1f).height(30.dp).background(if (active) Palette.Ink else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(9.dp)), radius = 9.dp, description = level.label, onClick = { choose(i) }) {
                            Txt(level.key, size = 10, color = if (active) androidx.compose.ui.graphics.Color.White else Palette.Secondary,
                                weight = if (active) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
            } else Txt(displayedLabel, size = 18, weight = FontWeight.Medium)
            if (running) Txt("当前回复继续使用原设置，下次发送生效", Modifier.padding(top = 4.dp), size = 11, color = Palette.Secondary)
        }
        }
    }
}

private inline fun chooseNearest(fraction: Float, marks: List<Float>, choose: (Int) -> Unit) {
    marks.indices.minByOrNull { abs(marks[it] - fraction) }?.let(choose)
}
