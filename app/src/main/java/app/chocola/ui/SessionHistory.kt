package app.chocola.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object SessionHistory {
    fun section(s: Session, today: LocalDate, zone: ZoneId): String {
        if (s.pinned) return "已置顶"
        val day = Instant.ofEpochMilli(s.updated).atZone(zone).toLocalDate()
        return when {
            day >= today -> "今天"
            day == today.minusDays(1) -> "昨天"
            day >= today.minusDays(7) -> "最近七天"
            else -> "更早"
        }
    }
    fun programs(state: AppState, id: String) = state.processes.filter { it.sessionId == id && it.status == "running" }
}

@Composable internal fun SessionHistoryRow(session: Session, state: AppState, onSelect: () -> Unit, onMore: (androidx.compose.ui.geometry.Rect) -> Unit) {
    val selected = session.id == state.currentId
    val phase = state.tasks[session.id]
    val programs = remember(state.processes, session.id) { SessionHistory.programs(state, session.id) }
    val time = remember(session.updated) {
        Instant.ofEpochMilli(session.updated).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val select by rememberUpdatedState(onSelect)
    val more by rememberUpdatedState(onMore)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)
        .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .semantics {
            contentDescription = session.title
            onClick { select(); true }
            onLongClick(label = "会话操作") { more(bounds); true }
        }
        .pointerInput(session.id) { detectTapGestures(onTap = { select() }, onLongPress = { point ->
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            val x = bounds.left + point.x; val y = bounds.top + point.y
            more(androidx.compose.ui.geometry.Rect(x - 8, y - 8, x + 8, y + 8))
        }) }.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (session.pinned) Glyph("pin", Modifier.size(11.dp), Palette.Secondary)
            Txt(session.title, Modifier.weight(1f), size = 14, weight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1, lineHeight = 20)
            when {
                phase != null -> HistoryBadge(if (phase == "等待授权") "待确认" else "运行中", true)
                session.draft.isNotBlank() -> HistoryBadge("草稿", false)
                session.branchOf.isNotBlank() -> HistoryBadge("分支", false)
            }
            if (programs.isNotEmpty()) HistoryBadge("后台 ${programs.size}", true)
            Txt(time, size = 10, color = Palette.Secondary, maxLines = 1, lineHeight = 16)
        }
    }
}

@Composable private fun HistoryBadge(text: String, active: Boolean) {
    Box(Modifier.height(18.dp).background(if (active) Color(0xFFE6EFE9) else Color(0xFFEAEBEE), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Txt(text, size = 10, color = if (active) Palette.Green else Palette.Secondary, maxLines = 1, lineHeight = 14)
    }
}
