package app.chocola.ui

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chocola.agent.AgentEngine
import app.chocola.agent.PermissionPolicy
import app.chocola.data.*
import kotlinx.coroutines.launch

internal fun accessDescription(access: Access) = when (access) {
    Access.CHAT -> "只进行对话，不调用执行工具"
    Access.ASK -> "默认读取免确认；编辑、命令和网络先询问"
    Access.WORKSPACE -> "默认自动编辑文件；命令和网络仍先询问"
    Access.PLAN -> "只读探索与规划，不执行命令或修改文件"
    Access.FULL -> "自动执行工具，仍遵守禁止与询问规则"
}

@Composable fun PermissionsPage(state: AppState, repository: Repository, onBack: () -> Unit, onStorage: () -> Unit, onRules: () -> Unit) {
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Entry("lock", "新会话默认模式", state.preferences.defaultAccess.label) { choosing = "access" }
            Entry("sliders", "工具规则", "${state.preferences.toolRules.size} 项") { onRules() }
            Entry("folder", "访问手机文件", if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) "已授权" else "未授权", onClick = onStorage)
        }
    }
    when (choosing) {
        "access" -> AccessPicker(state.preferences.defaultAccess, { choosing = null }) { scope.launch { repository.preferences(repository.state.value.preferences.copy(defaultAccess = it)) } }
    }
}

@Composable fun ToolRulesPage(state: AppState, repository: Repository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf<String?>(null) }
    val labels = linkedMapOf("" to "跟随模式", "ask" to "始终询问", "allow" to "自动允许", "deny" to "禁止")
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
            items((PermissionPolicy.knownTools + repository.mcp.schemas(Session(access = Access.ASK)).map { it.getString("name") }).toList(), key = { it }) { name -> Entry(text = AgentEngine.toolLabel(name), value = labels[state.preferences.toolRules[name].orEmpty()].orEmpty()) { tool = name } }
        }
    }
    tool?.let { name -> ChoiceSheet(labels.values.toList(), labels[state.preferences.toolRules[name].orEmpty()], { tool = null }) { label ->
        val value = labels.entries.first { it.value == label }.key
        scope.launch {
            val current = repository.state.value.preferences
            repository.preferences(current.copy(toolRules = if (value.isBlank()) current.toolRules - name else current.toolRules + (name to value)))
        }
    } }
}
