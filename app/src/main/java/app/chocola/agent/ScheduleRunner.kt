package app.chocola.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.chocola.ChocolaApp
import app.chocola.MainActivity
import app.chocola.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The OS owns the waiting period. No sleeping shell, periodic AI call or permanent service. */
class ScheduleRunner(private val repository: Repository) {
    private val gate = Mutex()
    @Volatile private var paused = false
    suspend fun <T> whilePaused(block: suspend () -> T): T = gate.withLock {
        paused = true; refresh()
        try { block() } finally { paused = false; refresh() }
    }
    private val context get() = repository.context
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    fun ready(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    private fun alarm(): PendingIntent = PendingIntent.getBroadcast(context, 41,
        Intent(context, ScheduleReceiver::class.java).setAction(ACTION_DUE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    suspend fun initialize() {
        repository.state.first { it.loaded }
        refresh()
    }
    @Synchronized fun refresh() {
        val allowed = ready()
        repository.alarmReady(allowed)
        alarms.cancel(alarm())
        val at = repository.state.value.schedules.filter { it.enabled && it.nextAt > 0 }.minOfOrNull { it.nextAt }
        if (!allowed || at == null || paused || !repository.state.value.preferences.setupCompleted) return
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(System.currentTimeMillis() + 1000, at), alarm())
            repository.logs.event("schedule.alarm", mapOf("at" to at))
        } catch (e: SecurityException) { repository.alarmReady(false); repository.logs.error("schedule.alarm.denied", e) }
    }
    suspend fun dispatchDue() = gate.withLock {
        repository.state.first { it.loaded }
        val now = System.currentTimeMillis()
        val due = repository.state.value.schedules.filter { it.enabled && it.nextAt in 1..now }
        for (original in due) {
            val task = repository.state.value.schedules.firstOrNull { it.id == original.id && it.enabled && it.nextAt == original.nextAt } ?: continue
            val validMcpTools = repository.mcp.schemas(Session(access = Access.ASK)).map { it.getString("name") }.toSet()
            val missingMcp = task.allowedTools.filter(McpNames::isTool).any { it !in validMcpTools }
            val outcome = when {
                now - task.nextAt > 15 * 60_000 || (task.endAt > 0 && now > task.endAt) -> "missed"
                task.activeRun.isNotBlank() || repository.engine.isRunning(task.resultSessionId) -> "busy"
                repository.state.value.providers.none { it.id == task.providerId && (task.modelSnapshot.isBlank() || it.model == task.modelSnapshot) } -> "failed"
                task.authorizationVersion == 0 || missingMcp -> "failed"
                else -> "running"
            }
            // Advance the occurrence before work starts, so duplicate broadcasts cannot run it twice.
            val runId = newId()
            var didClaim = false
            val claimed = repository.changeSchedule(task.id) { old ->
                if (!old.enabled || old.nextAt != task.nextAt) old else {
                    didClaim = true
                    ScheduleTimes.claim(old, now, outcome, runId)
                }
            } ?: continue
            if (!didClaim) continue
            repository.logs.event("schedule.trigger", mapOf("id" to task.id, "status" to outcome, "session" to task.sessionId))
            if (outcome == "running") {
                try { repository.prepareScheduleSession(claimed) } catch (e: Exception) {
                    repository.logs.error("schedule.prepare.failed", e)
                    repository.changeSchedule(task.id) { ScheduleTimes.complete(it, runId, "failed") }
                    continue
                }
                val started = repository.engine.send("定时任务：${task.title}\n本次计划时间：${ScheduleTimes.display(task.nextAt, task.zone)}（${task.zone}）\n请执行本次任务，不要重新创建同名定时任务：\n${task.prompt}",
                    sessionId = claimed.resultSessionId, executionTimeoutMinutes = task.timeoutMinutes, scheduled = true,
                    onFinished = { status ->
                        repository.changeSchedule(task.id) { current -> ScheduleTimes.complete(current, runId, status) }
                        repository.logs.event("schedule.finished", mapOf("id" to task.id, "status" to status))
                        notifyResult(claimed, status)
                    })
                if (!started) repository.changeSchedule(task.id) { ScheduleTimes.complete(it, runId, "busy") }
            } else if (outcome == "failed") {
                repository.changeSchedule(task.id) { it.copy(lastDetail = if (task.authorizationVersion == 0) "请编辑任务，完成执行权限检查" else if (missingMcp) "MCP 服务已停用或工具已变更，请重新授权任务" else "任务模型已变更或删除，请重新选择") }
                notifyResult(claimed, "failed")
            }
        }
        refresh()
    }
    suspend fun blocked(detail: String) = gate.withLock {
        repository.state.first { it.loaded }
        val now = System.currentTimeMillis()
        for (task in repository.state.value.schedules.filter { it.enabled && it.nextAt in 1..now }) {
            repository.changeSchedule(task.id) { current ->
                if (!current.enabled || current.nextAt != task.nextAt) current else {
                    val next = ScheduleTimes.claim(current, now, "failed", "")
                    if (next.activeRun.isBlank()) next.copy(lastDetail = detail) else next
                }
            }
        }
        refresh()
    }
    private suspend fun notifyResult(task: ScheduledTask, status: String) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("schedule-reports", "定时任务汇报", android.app.NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val open = PendingIntent.getActivity(context, task.resultSessionId.hashCode(), Intent(context, MainActivity::class.java).putExtra("sessionId", task.resultSessionId)
            .setData(Uri.parse("chocola://report/${task.resultSessionId}")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val summary = repository.state.value.messages[task.resultSessionId].orEmpty().lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text
            ?.let { Redactor.clean(it).replace(Regex("[#*`]"), "").take(220) }
            ?: task.lastDetail.ifBlank { ScheduleTimes.status(status) }
        runCatching {
            manager.notify(task.resultSessionId.hashCode(), android.app.Notification.Builder(context, "schedule-reports").setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(task.title).setContentText(summary).setStyle(android.app.Notification.BigTextStyle().bigText(summary))
                .setCategory(android.app.Notification.CATEGORY_MESSAGE).setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open).setAutoCancel(true).setWhen(System.currentTimeMillis()).build())
        }.onFailure { repository.logs.error("schedule.notification.failed", it) }
    }
    companion object { const val ACTION_DUE = "app.chocola.SCHEDULE_DUE" }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repository = (context.applicationContext as ChocolaApp).repository
        if (intent.action == ScheduleRunner.ACTION_DUE) {
            try { context.startForegroundService(Intent(context, TaskService::class.java).setAction(ScheduleRunner.ACTION_DUE)) }
            catch (e: Exception) {
                repository.logs.error("schedule.service.failed", e)
                val pending = goAsync()
                repository.scope.launch { try { repository.scheduler.blocked("系统阻止了定时任务启动，请检查后台运行权限") } finally { pending.finish() } }
            }
        } else {
            val pending = goAsync()
            repository.scope.launch { try { repository.state.first { it.loaded }; repository.scheduler.refresh() } finally { pending.finish() } }
        }
    }
}
