package app.chocola.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chocola.data.*
import kotlinx.coroutines.delay
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

internal val statusStyles = linkedMapOf("mercury" to "水银折光", "interference" to "交错光场", "cosmos" to "星云流体", "sheen" to "银光流动", "silk" to "丝绸折光", "glass" to "玻璃掠光", "scan" to "光束扫描", "ripple" to "交叠流光",
    "halo" to "环绕辉光", "breath" to "柔光呼吸", "mist" to "雾面游光", "tide" to "轻波流光", "aurora" to "极光漫游", "prism" to "棱镜流彩", "ember" to "香槟光泽", "still" to "静态")

@Composable internal fun StatusIndicator(style: String, speed: Int, strength: Float, modifier: Modifier = Modifier.size(18.dp)) {
    val transition = rememberInfiniteTransition(label = "status")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(speed.coerceIn(800, 4000), easing = LinearEasing)), label = "statusPhase")
    Canvas(modifier) {
        val p = if (style == "still") 0f else phase
        val angle = p * (2 * PI).toFloat()
        val pulse = (.5f + .5f * sin(angle)).coerceIn(0f, 1f)
        val alpha = .44f + pulse * strength.coerceIn(.15f, 1f) * .5f
        val c = center
        when (style) {
            "ring" -> drawArc(Palette.Ink.copy(alpha = .72f), p * 360, 215f + 55 * pulse, false, topLeft = c - Offset(5.dp.toPx(), 5.dp.toPx()), size = androidx.compose.ui.geometry.Size(10.dp.toPx(), 10.dp.toPx()), style = Stroke(1.25.dp.toPx(), cap = StrokeCap.Round))
            "orbit" -> {
                drawCircle(Palette.Ink.copy(alpha = .14f), 4.5.dp.toPx(), c, style = Stroke(.8.dp.toPx()))
                drawCircle(Palette.Ink.copy(alpha = .8f), 1.7.dp.toPx(), c + Offset(cos(angle), sin(angle)) * 4.5.dp.toPx())
                drawCircle(Palette.Ink.copy(alpha = .3f), 1.2.dp.toPx(), c - Offset(cos(angle), sin(angle)) * 4.5.dp.toPx())
            }
            "wave" -> repeat(3) { i ->
                val height = (3f + 5f * (.5f + .5f * sin(angle - i * .8f))).dp.toPx()
                val x = c.x + (i - 1) * 4.dp.toPx()
                drawLine(Palette.Ink.copy(alpha = .5f + pulse * .25f), Offset(x, c.y - height / 2), Offset(x, c.y + height / 2), 1.8.dp.toPx(), StrokeCap.Round)
            }
            "dots" -> repeat(3) { i -> drawCircle(Palette.Ink.copy(alpha = .25f + (.5f + .5f * sin(angle - i * .9f)) * .6f), 1.6.dp.toPx(), c + Offset((i - 1) * 5.dp.toPx(), 0f)) }
            "still" -> drawCircle(Palette.Ink.copy(alpha = .7f), 2.5.dp.toPx(), c)
            else -> {
                drawCircle(Brush.radialGradient(listOf(Palette.Ink.copy(alpha = alpha * .18f), Color.Transparent), center = c, radius = (6 + pulse * 2).dp.toPx()), (6 + pulse * 2).dp.toPx(), c)
                drawCircle(Palette.Ink.copy(alpha = alpha), (2.3f + pulse * .35f).dp.toPx(), c)
            }
        }
    }
}

@Composable internal fun StatusPill(text: String, active: Boolean, preferences: Preferences, modifier: Modifier = Modifier, preview: Boolean = false) {
    val logs = LocalDiagnostics.current
    val design = remember(preferences.pill) { preferences.pill.normalized() }
    var label by remember { mutableStateOf(text.removePrefix("正在")) }
    var shown by remember { mutableStateOf(preview && active) }
    var shownAt by remember { mutableLongStateOf(0L) }
    // Debounce each operation before changing the displayed label; preserve short gaps.
    LaunchedEffect(active, text, preview) {
        if (active && text.isNotBlank()) {
            if (!preview) delay(if (shown) 180 else 420)
            label = text.removePrefix("正在")
            if (!shown) shownAt = SystemClock.uptimeMillis()
            shown = true
        } else if (shown) {
            if (!preview) delay(maxOf(180L, 700L - (SystemClock.uptimeMillis() - shownAt)))
            shown = false
        }
    }
    var present by remember { mutableStateOf(shown) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(shown, design.appearanceDuration, design.entrance) {
        if (shown) present = true
        if (reveal.value != (if (shown) 1f else 0f)) logs.traceMotion("status_visibility", mapOf("shown" to shown, "preview" to preview,
            "entrance" to design.entrance, "configuredMs" to design.appearanceDuration)) {
            reveal.animateTo(if (shown) 1f else 0f, if (design.entrance == "still") snap() else tween(design.appearanceDuration, easing = CubicBezierEasing(.28f, 0f, .2f, 1f)))
        }
        if (!shown) present = false
    }
    if (!present) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val style = TextStyle(fontSize = (design.fontSize * LocalAppFontScale.current).sp, lineHeight = (design.fontSize * 1.5f * LocalAppFontScale.current).sp,
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight(design.fontWeight), letterSpacing = design.letterSpacing.sp,
        color = pillColor(design.ink), platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both))
    val textSize = remember(label, style, measurer) { measurer.measure(androidx.compose.ui.text.AnnotatedString(label), style, maxLines = 1).size }
    val labelWidth = textSize.width
    val target = with(density) { if (design.autoWidth) (labelWidth + (design.padding * 2).dp.toPx()).coerceIn(design.minWidth.dp.toPx(), design.maxWidth.dp.toPx()) else design.fixedWidth.dp.toPx() }
    val widthSpec: FiniteAnimationSpec<Float> = when (design.widthCurve) {
        "linear" -> tween(design.widthDuration, easing = LinearEasing)
        "ease" -> tween(design.widthDuration, easing = FastOutSlowInEasing)
        "spring" -> spring(dampingRatio = design.damping, stiffness = design.stiffness, visibilityThreshold = .5f)
        else -> tween(design.widthDuration, easing = CubicBezierEasing(.32f, 0f, .2f, 1f))
    }
    val light = rememberInfiniteTransition(label = "pillField")
    val phase = light.animateFloat(0f, 1f, infiniteRepeatable(tween(design.period, easing = LinearEasing)), label = "pillPhase")
    val widthTrace = remember { arrayOfNulls<app.chocola.data.TraceSpan>(1) }
    val previousWidth = remember { floatArrayOf(target) }
    LaunchedEffect(target) {
        if (previousWidth[0] != target) {
            widthTrace[0]?.finish("retargeted")
            widthTrace[0] = logs?.begin("motion", fields = mapOf("category" to "status_width", "fromPx" to previousWidth[0], "toPx" to target,
                "curve" to design.widthCurve, "configuredMs" to design.widthDuration, "labelChars" to label.length, "preview" to preview))
            previousWidth[0] = target
        }
    }
    DisposableEffect(Unit) { onDispose { widthTrace[0]?.finish("disposed"); widthTrace[0] = null } }
    val width = animateFloatAsState(target, widthSpec, label = "capsuleWidth", finishedListener = { final ->
        widthTrace[0]?.finish(fields = mapOf("finalPx" to final)); widthTrace[0] = null
    })
    val radius = minOf(design.corner, design.height / 2).dp
    val shape = RoundedCornerShape(radius)
    Layout(content = {
        Box(Modifier.fillMaxSize().padding(horizontal = design.padding.dp), contentAlignment = Alignment.Center) { StatusLabel(label, design, style, phase) }
    }, modifier = modifier.graphicsLayer {
        val p = reveal.value
        alpha = p
        val scaling = design.entrance == "lift" || design.entrance == "scale"
        scaleX = if (scaling) design.startScale + (1 - design.startScale) * p else 1f; scaleY = scaleX
        translationY = if (design.entrance == "lift") (1 - p) * design.travel.dp.toPx() else 0f
        clip = false; compositingStrategy = CompositingStrategy.ModulateAlpha
    }.softShadow(radius, true, design.shadowBlur.dp, design.shadowOffset.dp, design.shadowAlpha, pillColor(design.shadowColor))
        .background(pillColor(design.paper), shape).clip(shape).then(statusSurface(design, phase))) { children, constraints ->
        val w = width.value.roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val h = maxOf(design.height.dp.roundToPx(), textSize.height + 8.dp.roundToPx()).coerceIn(constraints.minHeight, constraints.maxHeight)
        val contentWidth = (if (design.autoWidth) design.maxWidth else design.fixedWidth).dp.roundToPx().coerceAtMost(constraints.maxWidth)
        val child = children.single().measure(Constraints.fixed(contentWidth, h))
        layout(w, h) { child.placeRelative((w - contentWidth) / 2, 0) }
    }
}

@Composable internal fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onValue: (Float) -> Unit) {
    val latest by rememberUpdatedState(onValue)
    var width by remember { mutableFloatStateOf(1f) }
    fun change(x: Float) { latest(range.start + (x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Txt(label, size = 14); Txt(display, size = 13, color = Palette.Secondary) }
        Canvas(Modifier.fillMaxWidth().height(42.dp).onSizeChanged { width = it.width.toFloat() }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, range); setProgress { latest(it.coerceIn(range)); true } }
            .pointerInput(range) { detectTapGestures { change(it.x) } }
            .pointerInput(range) { detectDragGestures(onDragStart = { change(it.x) }) { c, _ -> c.consume(); change(c.position.x) } }) {
            val radius = 6.dp.toPx()
            val y = center.y
            val left = radius
            val end = size.width - radius
            val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val x = left + (end - left) * fraction
            drawLine(Palette.Border, Offset(left, y), Offset(end, y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(Palette.Ink, Offset(left, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(Palette.Ink, radius, Offset(x, y))
        }
    }
}
