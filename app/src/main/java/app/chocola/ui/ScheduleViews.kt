package app.chocola.ui

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import kotlinx.coroutines.launch
import java.time.*

@Composable internal fun ScheduleRow(task: ScheduledTask, state: AppState, onEdit: () -> Unit, onMore: (Rect) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val edit by rememberUpdatedState(onEdit)
    val more by rememberUpdatedState(onMore)
    val haptic = LocalHapticFeedback.current
    val running = task.lastStatus == "running"
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
        .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .semantics { contentDescription = task.title; onClick { edit(); true }; onLongClick("定时任务操作") { more(bounds); true } }
        .pointerInput(task.id) { detectTapGestures(onTap = { edit() }, onLongPress = { point ->
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            val x = bounds.left + point.x; val y = bounds.top + point.y
            more(Rect(x - 8, y - 8, x + 8, y + 8))
        }) }.padding(horizontal = 15.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).background(Palette.Fill, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Glyph("clock", Modifier.size(16.dp), if (running) Palette.Green else Palette.Ink)
            }
            Txt(task.title, Modifier.weight(1f), size = 14, weight = FontWeight.Medium, maxLines = 1)
            val status = if (running) "执行中" else if (!task.enabled) "已暂停" else if (task.authorizationVersion == 0) "待授权" else if (task.nextAt == 0L) "已结束" else if (!state.scheduleAlarmReady) "待授权" else "已安排"
            val color = if (running) Palette.Green else Palette.Secondary
            Box(Modifier.background(if (running) color.copy(alpha = .08f) else Palette.Fill, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Txt(status, size = 10, color = color)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(ScheduleTimes.summary(task), Modifier.weight(1f), size = 11, color = Palette.Secondary, maxLines = 1)
            if (task.maxRuns > 0) Txt("${task.runCount}/${task.maxRuns}", size = 10, color = Palette.Secondary)
        }
        if (task.enabled && task.nextAt > 0) Txt("下次 ${ScheduleTimes.display(task.nextAt, task.zone)}", size = 11, color = Palette.Secondary, maxLines = 1)
        if (task.lastAt > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val failed = task.lastStatus in setOf("failed", "timeout", "interrupted")
            Box(Modifier.size(4.dp).background(if (failed) Palette.Red else Palette.Green, androidx.compose.foundation.shape.CircleShape))
            Txt(ScheduleTimes.status(task.lastStatus), size = 10, color = if (failed) Palette.Red else Palette.Secondary, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Txt(ScheduleTimes.display(task.lastAt, task.zone).substringAfter(' '), size = 10, color = Palette.Secondary)
        }
    }

}

@Composable internal fun SchedulePermissionCard(state: AppState, onRequest: () -> Unit) {
    if (!state.scheduleAlarmReady) Press(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), onClick = onRequest) {
        Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Txt("允许定时唤醒", size = 13, weight = FontWeight.Medium)
            Txt("开启系统“闹钟和提醒”权限，才能在后台按时执行。", size = 11, color = Palette.Secondary)
        }
    }
}

internal fun requestScheduleAlarm(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
    }
}

@Composable internal fun ScheduleEditor(initial: ScheduledTask, state: AppState, repository: Repository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var draftJson by rememberSaveable(initial.id) { mutableStateOf(initial.json().toString()) }
    val draft = remember(draftJson) { ScheduledTask.from(org.json.JSONObject(draftJson)) }
    fun update(change: (ScheduledTask) -> ScheduledTask) { draftJson = change(draft).json().toString() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }
    var choosingThinking by remember { mutableStateOf(false) }
    var choosingAccess by remember { mutableStateOf(false) }
    fun pickTime(value: Long, onPicked: (Long) -> Unit) {
        val zone = runCatching { ZoneId.of(draft.zone) }.getOrElse { error = "请先填写有效时区"; return }
        val date = Instant.ofEpochMilli(value).atZone(zone)
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                onPicked(LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli())
            }, date.hour, date.minute, true).show()
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    Sheet(onDismiss) { close ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(if (state.schedules.any { it.id == initial.id }) "编辑定时任务" else "添加定时任务", Modifier.weight(1f), weight = FontWeight.Medium)
            IconButton("close", "关闭编辑") { close() }
        }
        Segments(listOf("内容", "执行", "时间", "限制"), tab, { tab = it }, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 390.dp).verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            when (tab) {
                0 -> {
                    Field("名称", draft.title, { value -> update { it.copy(title = value) } }, placeholder = "例如：每日服务器检查")
                    Field("每次要做什么", draft.prompt, { value -> update { it.copy(prompt = value) } }, lines = 5, placeholder = "写清检查内容和结果要求")
                    if (draft.lastAt > 0) {
                        Txt("最近：${ScheduleTimes.status(draft.lastStatus)}", size = 12, color = Palette.Secondary)
                        if (draft.lastDetail.isNotBlank()) Txt(draft.lastDetail, size = 12, color = Palette.Secondary)
                    }
                }
                1 -> {
                    val model = state.providers.firstOrNull { it.id == draft.providerId }
                    Entry("sliders", "模型", model?.model ?: "选择模型") { choosingModel = !choosingModel; choosingAccess = false }
                    InlineMenu(choosingModel) {
                        ModelChoices(state.providers, draft.providerId, Modifier.heightIn(max = 200.dp)) { selected ->
                            update { it.copy(providerId = selected.id, modelSnapshot = selected.model,
                                reasoningLevel = state.preferences.reasoningDefaults[ThinkingControls.modelKey(selected)].orEmpty()) }
                            choosingModel = false
                        }
                    }
                    Entry("lock", "执行权限", draft.access.label) { choosingAccess = !choosingAccess; choosingModel = false }
                    InlineMenu(choosingAccess) {
                        Access.entries.forEach { access ->
                            Entry(text = access.label, value = if (access == draft.access) "已选择" else "", detail = accessDescription(access)) {
                                update { it.copy(access = access) }; choosingAccess = false
                            }
                        }
                    }
                    if (model != null) {
                        val levels = ThinkingControls.options(model)
                        val index = levels.indexOfFirst { it.key == draft.reasoningLevel }.coerceAtLeast(0)
                        Entry("sliders", "思考程度", levels[index].label) { choosingThinking = !choosingThinking; choosingAccess = false; choosingModel = false }
                        InlineMenu(choosingThinking) {
                            levels.forEach { level -> Entry(text = level.label, value = if (level.key == draft.reasoningLevel) "已选择" else "") {
                                update { it.copy(reasoningLevel = level.key) }; choosingThinking = false
                            } }
                        }
                    }
                    GroupLabel("使用的资料")
                    state.memories.filter { it.enabled }.forEach { memory ->
                        ToggleEntry(memory.name, memory.id in draft.credentialIds, detail = memory.kind) { selected ->
                            update { it.copy(credentialIds = if (selected) it.credentialIds + memory.id else it.credentialIds - memory.id) }
                        }
                    }
                }
                2 -> {
                    val frequencies = listOf("once", "interval", "daily", "weekly")
                    Segments(listOf("一次", "间隔", "每天", "每周"), frequencies.indexOf(draft.frequency).coerceAtLeast(0), { index -> update { it.copy(frequency = frequencies[index]) } }, Modifier.fillMaxWidth())
                    Entry("clock", "首次执行", runCatching { ScheduleTimes.display(draft.startAt, draft.zone) }.getOrDefault("选择时间")) { pickTime(draft.startAt) { value -> update { it.copy(startAt = value) } } }
                    if (draft.frequency == "interval") ScheduleNumber("间隔分钟（至少15）", draft.intervalMinutes) { value -> update { it.copy(intervalMinutes = value) } }
                    if (draft.frequency == "weekly") {
                        Txt("每周执行日", size = 13, color = Palette.Secondary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, day ->
                                val selected = index + 1 in draft.weekdays
                                Press(Modifier.weight(1f).height(44.dp), radius = 12.dp, description = "星期$day", onClick = { update { it.copy(weekdays = if (selected) it.weekdays - (index + 1) else it.weekdays + (index + 1)) } }) {
                                    Box(Modifier.size(32.dp).background(if (selected) Palette.Ink else Palette.Fill, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                        Txt(day, size = 13, color = if (selected) androidx.compose.ui.graphics.Color.White else Palette.Ink)
                                    }
                                }
                            }
                        }
                    }
                    Field("时区", draft.zone, { value -> update { it.copy(zone = value) } }, placeholder = "Asia/Shanghai")
                    ToggleEntry("启用计划", draft.enabled) { value -> update { it.copy(enabled = value) } }
                    SchedulePermissionCard(state) { runCatching { requestScheduleAlarm(context) }.onFailure { error = it.message.orEmpty() } }
                }
                else -> {
                    ToggleEntry("设置结束时间", draft.endAt > 0) { value -> update { it.copy(endAt = if (value) it.startAt + 7 * 86400000L else 0) } }
                    if (draft.endAt > 0) Entry("clock", "结束于", runCatching { ScheduleTimes.display(draft.endAt, draft.zone) }.getOrDefault("选择时间")) { pickTime(draft.endAt) { value -> update { it.copy(endAt = value) } } }
                    ScheduleNumber("最多执行次数（0为不限）", draft.maxRuns) { value -> update { it.copy(maxRuns = value) } }
                    ScheduleNumber("每次最长运行分钟", draft.timeoutMinutes) { value -> update { it.copy(timeoutMinutes = value) } }
                    Txt("已启动 ${draft.runCount} 次。结束时间控制何时停止触发；单次时限控制一轮最多运行多久，每次执行生成独立汇报。", size = 12, color = Palette.Secondary)
                    Txt("本机执行，需保持设备开机和联网。迟到超过15分钟会记为错过，不集中补跑；上一次执行未结束时跳过。", size = 12, color = Palette.Secondary)
                }
            }
        }
        if (error.isNotBlank()) Txt(error, Modifier.padding(bottom = 10.dp), size = 12, color = Palette.Red)
        ActionButton(if (initial.id in state.scheduleChecks) "正在检查执行条件" else if (saving) "正在保存" else "保存", Modifier.fillMaxWidth(), enabled = !saving && draft.title.isNotBlank() && draft.prompt.isNotBlank() && draft.providerId.isNotBlank()) {
            saving = true; error = ""
            scope.launch {
                try {
                    repository.saveSchedule(draft)
                    if (!repository.scheduler.ready()) repository.notice("任务已保存，请在定时任务页允许定时唤醒")
                    close()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "保存失败" }
                finally { saving = false }
            }
        }
    }

}

@Composable private fun ScheduleNumber(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by rememberSaveable(label) { mutableStateOf(value.toString()) }
    Field(label, text, { raw -> if (raw.length <= 6 && raw.all(Char::isDigit)) { text = raw; onChange(raw.toIntOrNull() ?: 0) } }, keyboard = KeyboardType.Number)
}
