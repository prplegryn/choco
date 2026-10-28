package app.chocola.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun MathRichText(content: AnnotatedString, modifier: Modifier, style: TextStyle,
    streaming: Boolean, animate: Boolean, animateInitial: Boolean) {
    val expressions = remember(content, streaming) { MathTypesetter.expressions(content, streaming) }
    if (expressions.isEmpty()) { RenderText(content, modifier, style, animate, animateInitial = animateInitial); return }
    val resolved by produceState<Map<MathExpression, MathResult>>(emptyMap(), expressions) {
        value = withContext(Dispatchers.Default) { expressions.values.filter { it.complete }.associateWith(MathTypesetter::render) }
    }
    val density = LocalDensity.current
    val size = with(density) { style.fontSize.toPx() }
    val textCenter = remember(size) {
        android.graphics.Paint().apply { textSize = size; typeface = android.graphics.Typeface.DEFAULT }.fontMetrics.let { (it.ascent + it.descent) / 2f }
    }
    BoxWithConstraints(modifier) {
        val available = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
        val inline = expressions.mapNotNull { (id, expression) ->
            val result = if (expression.complete) MathTypesetter.peek(expression) ?: resolved[expression] else null
            val math = result?.layout
            if (result != null && math == null) null else {
                val factor = size / MathTypesetter.Em
                val metrics = if (math == null) InlineMathBox(size * .95f, size, 0f)
                    else inlineMathBox(math.width * factor, math.height * factor, math.baseline * factor, textCenter)
                val width = minOf(metrics.width, available)
                id to InlineTextContent(Placeholder(with(density) { width.toSp() }, with(density) { metrics.height.toSp() }, PlaceholderVerticalAlign.TextCenter)) {
                    if (math == null) FormulaPending(Modifier.fillMaxSize(), style.color)
                    else {
                        val scroll = rememberScrollState()
                        val overflow = metrics.width > available
                        Box(Modifier.fillMaxSize().then(if (overflow) Modifier.clip(RoundedCornerShape(4.dp)) else Modifier).mathFades(scroll)) {
                            FormulaGraphic(expression, math, size, metrics.top, style.color, animate && animateInitial,
                                Modifier.horizontalScroll(scroll).then(if (overflow) Modifier.padding(horizontal = 4.dp) else Modifier)
                                    .requiredSize(with(density) { metrics.width.toDp() }, with(density) { metrics.height.toDp() }))
                        }
                    }
                }
            }
        }.toMap()
        RenderText(content, Modifier.fillMaxWidth(), style, animate, animateInitial = animateInitial, inlineContent = inline)
    }
}

@Composable internal fun MathBlock(expression: MathExpression, streaming: Boolean, animate: Boolean) {
    val resolved by produceState<Pair<MathExpression, MathResult>?>(null, expression) {
        value = if (expression.complete) withContext(Dispatchers.Default) { expression to MathTypesetter.render(expression) } else null
    }
    val result = if (expression.complete) MathTypesetter.peek(expression) ?: resolved?.takeIf { it.first == expression }?.second else null
    val clipboard = LocalClipboardManager.current
    fun copy() { clipboard.setText(AnnotatedString(expression.source.trim())) }
    val color = LocalTranscriptInk.current
    val density = LocalDensity.current
    val size = with(density) { (18 * LocalAppFontScale.current).sp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth().animateContentSize(tween(Motion.Expand, easing = Motion.Travel)).clipToBounds()
        .combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClickLabel = "复制公式", onLongClick = ::copy)
        .semantics { contentDescription = expression.latex; customActions = listOf(CustomAccessibilityAction("复制公式") { copy(); true }) }) {
        val viewportWidth = maxWidth
        val math = result?.layout
        if ((!expression.complete && streaming) || (expression.complete && result == null)) {
            Box(Modifier.fillMaxWidth().height(42.dp), contentAlignment = Alignment.Center) { FormulaPending(Modifier.size(25.dp, 18.dp), color) }
        } else if (math != null) {
            val width = with(density) { (math.width * size / MathTypesetter.Em).toDp() }
            val height = with(density) { (math.height * size / MathTypesetter.Em).toDp() }
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).mathFades(scroll)) {
                Box(Modifier.horizontalScroll(scroll).widthIn(min = viewportWidth).padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    FormulaGraphic(expression, math, size, 0f, color, animate, Modifier.size(width, height))
                }
            }
        } else {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).mathFades(scroll)) {
                RenderText(AnnotatedString(expression.source), Modifier.horizontalScroll(scroll).padding(vertical = 8.dp),
                    TextStyle(color = color, fontFamily = FontFamily.Monospace, fontSize = (13 * LocalAppFontScale.current).sp, lineHeight = (22 * LocalAppFontScale.current).sp))
            }
        }
    }
}

@Composable private fun FormulaGraphic(expression: MathExpression, math: MathLayout, fontPixels: Float, top: Float, color: Color, animate: Boolean, modifier: Modifier) {
    val opacity = remember(expression.latex) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(expression.latex) { if (opacity.value < 1f) opacity.animateTo(1f, tween(200, easing = Motion.Travel)) }
    Canvas(modifier.graphicsLayer { alpha = opacity.value }) {
        val native = drawContext.canvas.nativeCanvas
        val save = native.save()
        try { native.translate(0f, top); math.draw(native, fontPixels, color.toArgb()) }
        finally { native.restoreToCount(save) }
    }
}

@Composable private fun FormulaPending(modifier: Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "formulaPending")
    val alpha by transition.animateFloat(.28f, .6f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "formulaBreath")
    Canvas(modifier) {
        val radius = minOf(1.3.dp.toPx(), size.height / 9f)
        repeat(3) { index -> drawCircle(color.copy(alpha = alpha), radius, Offset(size.width / 2f + (index - 1) * radius * 3.5f, size.height / 2f)) }
    }
}

/** Alpha masks preserve wallpaper and rounded edges; the left edge stays clear until scrolled. */
private fun Modifier.mathFades(scroll: ScrollState): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
    val feather = minOf(22.dp.toPx(), size.width / 4f).coerceAtLeast(1f)
    onDrawWithContent {
        drawContent()
        if (scroll.maxValue != Int.MAX_VALUE && scroll.maxValue > 0) {
            val left = (scroll.value / feather).coerceIn(0f, 1f)
            val right = ((scroll.maxValue - scroll.value) / feather).coerceIn(0f, 1f)
            if (left > 0f) drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 1f - left), Color.White), endX = feather), blendMode = BlendMode.DstIn)
            if (right > 0f) drawRect(Brush.horizontalGradient(listOf(Color.White, Color.White.copy(alpha = 1f - right)), startX = size.width - feather, endX = size.width), blendMode = BlendMode.DstIn)
        }
    }
}
