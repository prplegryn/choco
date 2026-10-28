package app.chocola.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chocola.agent.AgentEngine
import app.chocola.ChocolaApp
import app.chocola.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val operationFill = Color(0xFFF6F6F7)
internal fun operationSummary(step: OperationStep): String = operationCommand(step.call, step.arguments).take(2048).replace(Regex("\\s+"), " ").trim()
internal fun operationCommand(call: ToolCall?, arguments: JSONObject? = null): String {
    if (call == null) return ""
    val a = arguments ?: runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())
    return a.optString("command").ifBlank {
        if (call.name == "http_request") "${a.optString("method", "GET")} ${a.optString("url")}".trim()
        else a.optString("path").ifBlank { a.optString("url").ifBlank { a.optString("query").ifBlank { a.optString("name").ifBlank { call.remoteName.ifBlank { call.name } } } } }
    }
}
private fun operationShape(top: Boolean = false, bottom: Boolean = false) = RoundedCornerShape(if (top) 18.dp else 0.dp, if (top) 18.dp else 0.dp, if (bottom) 18.dp else 0.dp, if (bottom) 18.dp else 0.dp)

@Composable internal fun ExpandControl(expanded: Boolean, description: String, group: Boolean = false, onClick: () -> Unit) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.Expand, easing = Motion.Travel), label = "expandArrow")
    Press(Modifier.size(48.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }, radius = 22.dp,
        description = description, onClick = onClick) {
        Box(Modifier.size(if (group) 34.dp else 28.dp).background(Color.White, RoundedCornerShape(if (group) 8.dp else 6.dp)), contentAlignment = Alignment.Center) {
            Glyph("down", Modifier.size(if (group) 19.dp else 16.dp).graphicsLayer { rotationZ = angle }, Palette.Secondary)
        }
    }
}

@Composable internal fun OperationsCard(item: OperationsHeader, onGroup: () -> Unit) {
    val group = item.group
    val active = group.steps.any { it.message.status == "running" }
    val failed = group.steps.count { it.message.status in setOf("error", "denied", "interrupted") }
    val done = group.steps.count { it.message.status == "done" }
    val stopped = group.steps.count { it.message.status == "stopped" }
    val bottom by animateFloatAsState(if (item.expanded) 0f else 18f, tween(Motion.Expand, easing = Motion.Travel), label = "groupCorners")
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp).background(operationFill, RoundedCornerShape(18.dp, 18.dp, bottom.dp, bottom.dp))
        .padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Press(Modifier.weight(1f).height(52.dp), radius = 12.dp, description = if (item.expanded) "收起操作组" else "展开操作组", onClick = onGroup) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (active) StatusIndicator("ring", 1800, .5f, Modifier.size(15.dp)) else Glyph(if (failed > 0) "info" else "check", Modifier.size(15.dp), if (failed > 0) Palette.Red else Palette.Secondary)
                Txt(if (active) "正在执行 ${group.steps.size} 项操作" else if (failed > 0) "$done 项完成  $failed 项需查看" else if (stopped > 0) "$done 项完成  $stopped 项已停止" else "已完成 $done 项操作", size = 13, color = Palette.Secondary, maxLines = 1)
            }
        }
        ExpandControl(item.expanded, if (item.expanded) "收起操作组" else "展开操作组", group = true, onClick = onGroup)
    }
}

@Composable internal fun OperationRowView(item: OperationRow, animateEntry: Boolean, onClick: () -> Unit, onFull: () -> Unit) {
    val visible = remember(item.key) { MutableTransitionState(if (animateEntry) false else item.groupExpanded) }
    SideEffect { visible.targetState = item.groupExpanded }
    val ink = remember(item.key) { Animatable(if (animateEntry) 0f else if (item.groupExpanded) 1f else 0f) }
    LaunchedEffect(item.groupExpanded) {
        if (item.groupExpanded && ink.value < 1f) ink.animateTo(1f, tween(Motion.Expand, delayMillis = RevealMotion.Lag, easing = Motion.Travel))
        else if (!item.groupExpanded) ink.animateTo(0f, tween(75))
    }
    val detailOpen = item.expanded && item.groupExpanded
    val detailInk by animateFloatAsState(if (detailOpen) 1f else 0f,
        tween(if (detailOpen) Motion.Expand else 75, delayMillis = if (detailOpen) RevealMotion.Lag else 0, easing = Motion.Travel), label = "detailInk")
    AnimatedVisibility(visible, modifier = Modifier.padding(horizontal = 22.dp).graphicsLayer { shape = operationShape(bottom = item.last); clip = true },
        enter = expandVertically(tween(Motion.Expand, easing = Motion.Travel), expandFrom = Alignment.Top),
        exit = shrinkVertically(tween(Motion.Expand, easing = Motion.Travel), shrinkTowards = Alignment.Top)) {
        Column(Modifier.fillMaxWidth().background(operationFill)) {
            Column(Modifier.menuInk { ink.value }) { OperationStepLine(item.step, item.expanded, onClick) }
            AnimatedVisibility(detailOpen,
                enter = expandVertically(tween(Motion.Expand, easing = Motion.Travel), expandFrom = Alignment.Top),
                exit = shrinkVertically(tween(Motion.Expand, easing = Motion.Travel), shrinkTowards = Alignment.Top)) {
                Box(Modifier.menuInk { minOf(ink.value, detailInk) }) { OperationDetailView(item.step, onFull) }
            }
        }
    }
}

@Composable private fun OperationStepLine(step: OperationStep, expanded: Boolean, onClick: () -> Unit) {
    val label = step.arguments.optString(step.call?.descriptionKey?.ifBlank { "description" } ?: "description").ifBlank { step.call?.displayName?.ifBlank { null } ?: AgentEngine.toolLabel(step.message.toolName) }
    val command = remember(step.call) { operationSummary(step) }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 3.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                Txt(label, Modifier.background(Color(0xFFECECEE), RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 4.dp), size = 12,
                    color = if (step.message.status in setOf("error", "denied")) Palette.Red else Palette.Ink, weight = FontWeight.Medium, maxLines = 1)
            }
            ExpandControl(expanded, if (expanded) "收起操作详情" else "展开操作详情", onClick = onClick)
        }
        Spacer(Modifier.height(3.dp))
        CommandPreview(command, Modifier.fillMaxWidth().padding(end = 10.dp))
    }
}

@Composable private fun CommandPreview(command: String, modifier: Modifier) {
    var overflow by remember(command) { mutableStateOf(false) }
    BasicText(command, modifier.drawWithContent {
        drawContent()
        if (overflow) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, operationFill), startX = (size.width - 44.dp.toPx()).coerceAtLeast(0f), endX = size.width))
    }, TextStyle(color = Palette.Secondary, fontSize = (12 * LocalAppFontScale.current).sp, lineHeight = (19 * LocalAppFontScale.current).sp,
        fontFamily = FontFamily.Monospace, platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = 1, softWrap = false,
        overflow = TextOverflow.Clip, onTextLayout = { overflow = it.didOverflowWidth })
}

internal fun operationOutput(message: Message): String = runCatching { JSONObject(message.text).let { it.optString("output").ifBlank { it.optString("body").ifBlank { message.text } } } }.getOrDefault(message.text)

@Composable private fun OperationDetailView(step: OperationStep, onFull: () -> Unit) {
    val command = remember(step.call) { operationCommand(step.call, step.arguments) }
    val output by produceState("", step.message.text) { value = withContext(Dispatchers.Default) { operationOutput(step.message) } }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (command.isNotBlank()) InlineResultBlock("命令", command, 6)
        if (step.message.error.isNotBlank()) Txt(step.message.error, Modifier.padding(horizontal = 2.dp), size = 12, color = Palette.Red, maxLines = 4)
        if (output.isNotBlank()) InlineResultBlock("结果", output, 8)
        Press(Modifier.fillMaxWidth().height(40.dp), radius = 10.dp, description = "查看完整命令和结果", onClick = onFull) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("完整结果", Modifier.weight(1f), size = 12, color = Palette.Secondary); Glyph("right", Modifier.size(14.dp), Palette.Secondary)
            }
        }
    }
}

@Composable private fun InlineResultBlock(label: String, text: String, lines: Int) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(label, Modifier.weight(1f), size = 12, color = Palette.Secondary)
            CopyControl(text, "复制$label")
        }
        SelectionContainer { RenderText(AnnotatedString(text.take(5000)), Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            toolTextStyle(), maxLines = lines) }
    }
}

@Composable internal fun CopyControl(text: String, description: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Press(Modifier.size(40.dp), enabled = text.isNotEmpty(), radius = 20.dp, description = if (copied) "已复制" else description, onClick = {
        runCatching { clipboard.setText(AnnotatedString(text)) }.onSuccess { copied = true }
            .onFailure { (context.applicationContext as? ChocolaApp)?.repository?.notice("内容过长，暂时无法复制") }
    }) {
        CopyGlyph(copied)
    }
}

@Composable internal fun toolTextStyle() = TextStyle(color = Palette.Ink, fontSize = (13 * LocalAppFontScale.current).sp,
    lineHeight = (22 * LocalAppFontScale.current).sp, fontFamily = FontFamily.Monospace, platformStyle = PlatformTextStyle(includeFontPadding = false))
