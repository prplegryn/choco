package app.chocola.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Real composer and wallpaper preview share one optical centre and the same control geometry. */
@Composable internal fun ComposerActions(access: String, effort: Float, running: Boolean, canSend: Boolean, hasModel: Boolean = true,
    accessHidden: Boolean = false, thinkingHidden: Boolean = false, submitting: Boolean = false,
    onAttach: () -> Unit = {}, onThink: (Rect) -> Unit = {}, onAccess: (Rect) -> Unit = {}, onSend: () -> Unit = {}, onStop: () -> Unit = {},
    onBounds: (String, Rect) -> Unit = { _, _ -> }) {
    var thinkingBounds by remember { mutableStateOf(Rect.Zero) }
    var accessBounds by remember { mutableStateOf(Rect.Zero) }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton("attach", "添加附件", onClick = onAttach)
        Press(Modifier.size(48.dp), enabled = hasModel, radius = 24.dp, description = "调整思考程度", onClick = { onThink(thinkingBounds) }) {
            Box(Modifier.size(26.dp).graphicsLayer { alpha = if (thinkingHidden) 0f else 1f }
                .onGloballyPositioned { thinkingBounds = it.boundsInRoot(); onBounds("thinking", thinkingBounds) }, contentAlignment = Alignment.Center) {
                ThinkingGauge(effort, Modifier.size(24.dp))
            }
        }
        Press(Modifier.height(48.dp).widthIn(min = 64.dp), radius = 12.dp, description = "选择会话权限", onClick = { onAccess(accessBounds) }) {
            Box(Modifier.height(28.dp).padding(horizontal = 7.dp).graphicsLayer { alpha = if (accessHidden) 0f else 1f }
                .onGloballyPositioned { accessBounds = it.boundsInRoot(); onBounds("access", accessBounds) }, contentAlignment = Alignment.Center) {
                Txt(access, size = 12, weight = FontWeight.Medium, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(running && canSend, enter = expandHorizontally(tween(220, easing = Motion.Travel)) + fadeIn(tween(170)),
            exit = shrinkHorizontally(tween(220, easing = Motion.Travel)) + fadeOut(tween(130))) {
            Press(Modifier.size(40.dp), radius = 20.dp, description = "停止当前任务", onClick = onStop) { Glyph("stop", Modifier.size(17.dp)) }
        }
        Press(Modifier.size(56.dp), enabled = (canSend && !submitting) || (running && !canSend), radius = 28.dp,
            description = if (running && canSend) "插入指令" else if (running) "停止" else "发送", onClick = if (running && !canSend) onStop else onSend) {
            Box(Modifier.size(46.dp).background(Color(0xFF17181A), CircleShape), contentAlignment = Alignment.Center) { SendGlyph(running && !canSend, canSend || running, inserting = running && canSend) }
        }
    }
}
