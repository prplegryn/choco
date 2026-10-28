package app.chocola.ui

import android.app.NotificationManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.chocola.agent.*
import app.chocola.data.*
import kotlinx.coroutines.launch

@Composable internal fun ScheduleGrantSheet(request: ScheduleAuthorization, state: AppState, repository: Repository,
    onNotifications: () -> Unit, onAlarm: () -> Unit, onPower: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val notifications = remember(revision) { BackgroundAccess.notificationsEnabled(context) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel("schedule-reports")?.importance != NotificationManager.IMPORTANCE_NONE }
    val alarm = remember(revision) { repository.scheduler.ready() }
    val power = remember(revision) { BackgroundAccess.batteryExempt(context) }
    var tools by remember(request.id) { mutableStateOf(request.requirements.tools + request.task.allowedTools) }
    var credentials by remember(request.id) { mutableStateOf(request.requirements.credentials + request.task.credentialIds) }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var result by remember { mutableStateOf<ScheduleConsent?>(null) }
    var allTools by remember { mutableStateOf(false) }
    val selected = state.memories.filter { it.id in credentials }
    val kind = request.requirements.credentialKind
    val missing = selected.flatMap(ScheduleRequirements::missing) + request.requirements.issues +
        if (request.requirements.needsCredential && selected.none { kind.isBlank() || it.kind == kind }) listOf("选择执行使用的${kind.ifBlank { "凭据" }}资料") else emptyList()
    Sheet({ repository.answerSchedule(request.id, result) }) { close ->
        Txt(request.task.title, Modifier.padding(horizontal = 8.dp, vertical = 10.dp), size = 16, weight = androidx.compose.ui.text.font.FontWeight.Medium)
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            GroupLabel("使用的资料")
            state.memories.filter { it.enabled }.forEach { memory ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ToggleEntry(memory.name, memory.id in credentials, detail = memory.kind) {
                        credentials = if (it) credentials + memory.id else credentials - memory.id
                    } }
                    IconButton("edit", "编辑 ${memory.name}", Modifier.size(40.dp)) { editing = memory }
                }
            }
            Entry("plus", "添加资料或凭据") {
                editing = if ("ssh_execute" in tools) Memory(kind = "服务器", fields = listOf(MemoryField("host", ""), MemoryField("username", "root"), MemoryField("password", "", true), MemoryField("private_key", "", true))) else Memory()
            }
            GroupLabel("此任务可自动执行")
            val shown = if (allTools) (PermissionPolicy.knownTools + repository.mcp.schemas(Session(access = Access.ASK)).map { it.getString("name") }) - PermissionPolicy.scheduleTools - "start_process" else tools + request.requirements.tools
            shown.sorted().forEach { tool ->
                ToggleEntry(AgentEngine.toolLabel(tool), tool in tools, detail = if (state.preferences.toolRules[tool] in setOf("ask", "deny")) "仅对这个任务单独授权" else "") {
                    tools = if (it) tools + tool else tools - tool
                }
            }
            Entry("sliders", if (allTools) "收起其它工具" else "其它工具") { allTools = !allTools }
            GroupLabel("系统权限")
            Entry("notification", "汇报通知", if (notifications) "已允许" else "允许", onClick = onNotifications)
            Entry("clock", "按时唤醒", if (alarm) "已允许" else "允许", onClick = onAlarm)
            Entry("battery", "后台运行", if (power) "已允许" else "允许", onClick = onPower)
            missing.forEach { Txt(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), size = 12, color = Palette.Red) }
        }
        Spacer(Modifier.height(12.dp))
        ActionButton(if (!notifications || !alarm || !power) "继续授权" else "授权并验证访问", Modifier.fillMaxWidth(),
            enabled = missing.isEmpty() && tools.containsAll(request.requirements.tools) && credentials.containsAll(request.requirements.credentials)) {
            when {
                !notifications -> onNotifications()
                !alarm -> onAlarm()
                !power -> onPower()
                else -> { result = ScheduleConsent(tools, credentials); close() }
            }
        }
    }
    editing?.let { memory -> Sheet({ editing = null }) { _ ->
        Box(Modifier.fillMaxWidth().heightIn(max = 600.dp).height(520.dp)) {
            MemoryEditor(memory, state, repository, { editing = null }) { saved -> scope.launch {
                try { repository.saveMemory(saved); credentials = credentials + saved.id; editing = null }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { repository.notice(e.message ?: "资料保存失败") }
            } }
        }
    } }
}
