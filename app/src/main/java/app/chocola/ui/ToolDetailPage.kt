package app.chocola.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chocola.agent.AgentEngine
import app.chocola.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ToolDetailPage(repository: Repository, sessionId: String, messageId: String, onBack: () -> Unit) {
    var selected by rememberSaveable(sessionId, messageId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val live by remember(repository, scope, sessionId, messageId) { repository.state.project(scope, 100) { it.messages[sessionId]?.firstOrNull { m -> m.id == messageId } } }.collectAsStateWithLifecycle()
    val details by produceState<Pair<Message, ToolCall?>?>(null, sessionId, messageId) { value = repository.toolDetails(sessionId, messageId) }
    val message = live ?: details?.first
    val call = details?.second
    val document by produceState<ToolDocument?>(null, message, call) { value = withContext(Dispatchers.Default) { ToolDocument.prepare(message, call) } }
    val overview = rememberLazyListState()
    val parameters = rememberLazyListState()
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(onBack)
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Txt(call?.displayName?.ifBlank { null } ?: AgentEngine.toolLabel(message?.toolName.orEmpty()), Modifier.weight(1f), size = 16, weight = FontWeight.Medium, maxLines = 1)
            message?.let { StatusBadge(it.status) }
        }
        Segments(listOf("总览", "参数"), selected, { selected = it }, Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        TabContent(selected, Modifier.weight(1f)) { tab ->
            if (document != null) LazyColumn(Modifier.fillMaxSize(), state = if (tab == 0) overview else parameters,
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 24.dp)) {
                if (tab == 0) {
                    toolSection("command", requireNotNull(document).command)
                    item("section-gap") { Spacer(Modifier.height(16.dp)) }
                    toolSection("result", requireNotNull(document).result)
                } else toolSection("parameters", requireNotNull(document).parameters)
            }
        }
    }
}

@Composable private fun StatusBadge(status: String) {
    val label = mapOf("done" to "完成", "running" to "执行中", "error" to "失败", "denied" to "未授权", "interrupted" to "已中断", "stopped" to "已停止")[status] ?: status
    Txt(label, Modifier.background(Palette.Fill, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 4.dp), size = 12,
        color = if (status in setOf("error", "denied")) Palette.Red else Palette.Secondary)
}

private fun LazyListScope.toolSection(id: String, section: ToolSection) {
    item("$id:header") {
        Row(Modifier.fillMaxWidth().background(operationFill, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).height(46.dp)
            .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(section.label, Modifier.weight(1f), size = 13, color = Palette.Secondary, weight = FontWeight.Medium)
            CopyControl(section.text, "复制${section.label}")
        }
    }
    itemsIndexed(section.chunks, key = { index, _ -> "$id:$index" }) { index, part ->
        val last = index == section.chunks.lastIndex
        Box(Modifier.fillMaxWidth().background(operationFill, RoundedCornerShape(bottomStart = if (last) 14.dp else 0.dp, bottomEnd = if (last) 14.dp else 0.dp))
            .padding(start = 14.dp, end = 14.dp, bottom = if (last) 14.dp else 0.dp)) {
            SelectionContainer { RenderText(AnnotatedString(part), Modifier.fillMaxWidth(), toolTextStyle()) }
        }
    }
}
