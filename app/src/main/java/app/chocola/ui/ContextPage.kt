package app.chocola.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chocola.agent.ContextPlanner
import app.chocola.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ContextPage(repository: Repository, state: AppState, onBack: () -> Unit, onModel: () -> Unit) {
    val session = state.current ?: return
    val provider = state.currentProvider ?: return
    var showSummary by remember { mutableStateOf(false) }
    val usage by produceState(0, session.id, session.summaryAt, state.phase) {
        value = withContext(Dispatchers.IO) { ContextPlanner.used(repository.contextTail(session.id, session.summaryThrough, session.summaryUserId), session.summaryAt, ContextPlanner.tokens(session.summary) + 3000) }
    }
    val window = ContextPlanner.window(provider)
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item { InfoPair("模型", provider.label) }
            item { InfoPair("有效上下文估计", "${usage / 1000}K / ${window / 1000}K token") }
            item { InfoPair("自动整理", if (provider.autoCompact) "约 ${ContextPlanner.threshold(provider) * 100 / window}% 时整理" else "关闭") }
            item { InfoPair("优先保留", "最近 ${provider.recentTurns} 轮") }
            item { InfoPair("已保存摘要", if (session.summary.isBlank()) "尚未整理" else "${session.summary.length} 字") }
            item { Entry("sliders", "调整模型与上下文", onClick = onModel) }
            if (session.summary.isNotBlank()) item { Entry("file", "查看当前摘要") { showSummary = true } }
            item { ActionButton(if (session.id in state.tasks) "任务运行中" else "立即整理", Modifier.fillMaxWidth().padding(top = 14.dp), solid = false, enabled = session.id !in state.tasks) { repository.engine.compact() } }
        }
    }
    if (showSummary) Sheet({ showSummary = false }) { _ ->
        SelectionContainer { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { Markdown(session.summary, Modifier.padding(vertical = 12.dp)) } }
    }
}
