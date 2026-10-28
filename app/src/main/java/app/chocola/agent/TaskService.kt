package app.chocola.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.chocola.ChocolaApp
import app.chocola.MainActivity
import app.chocola.data.AppState
import app.chocola.data.newId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap

/** Work starts only after the foreground notification and wake lock are active. */
class TaskService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (application as ChocolaApp).repository
    private var wakeLock: PowerManager.WakeLock? = null
    private var promoted = false
    private var acquiredAt = 0L
    private fun wakeSnapshot(reason: String) {
        val fields = mapOf("reason" to reason, "promoted" to promoted, "held" to (wakeLock?.isHeld == true),
            "acquiredMonoMs" to acquiredAt, "leaseMs" to 30 * 60 * 1000L)
        repository.logs.serviceState = repository.logs.serviceState + fields
        repository.logs.event("service.wake_lock", fields)
    }
    private var dispatching = false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tasks", "后台任务", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        repository.logs.event("service.created", BackgroundAccess.snapshot(this))
        serviceScope.launch {
            repository.state.map { s -> Triple(s.tasks, s.activeProcessCount, s.sessions.map { it.id to it.title }) }.distinctUntilChanged().collect {
                if (promoted) {
                    if (!repository.state.value.hasBackgroundWork && !dispatching && pending.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE); promoted = false; stopSelf()
                    } else getSystemService(NotificationManager::class.java).notify(7, notification(repository.state.value))
                }
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(10 * 60 * 1000L)
                if (promoted) { wakeLock?.acquire(30 * 60 * 1000L); acquiredAt = android.os.SystemClock.elapsedRealtime(); wakeSnapshot("renew"); repository.logs.event("service.heartbeat", BackgroundAccess.snapshot(this@TaskService)) }
            }
        }
    }
    private fun notification(state: AppState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TaskService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val task = state.tasks.entries.firstOrNull()
        val title = if (state.tasks.size > 1) "${state.tasks.size} 个会话正在运行" else state.sessions.firstOrNull { it.id == task?.key }?.title ?: "后台任务"
        val text = listOfNotNull(task?.value, if (state.activeProcessCount > 0) "${state.activeProcessCount} 个后台进程" else null).joinToString("，").ifBlank { "正在继续任务" }
        val builder = Notification.Builder(this, "tasks")
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "停止全部", stop).build())
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).setShowWhen(false).build()
    }
    private fun promote() {
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, notification(repository.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, notification(repository.state.value))
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chocola:agent").apply {
            setReferenceCounted(false)
            if (Build.VERSION.SDK_INT >= 33) runCatching {
                setStateListener(java.util.concurrent.Executor { it.run() }) { enabled ->
                    repository.logs.serviceState = repository.logs.serviceState + ("effective" to enabled)
                    repository.logs.event("service.wake_lock_effective", mapOf("enabled" to enabled, "held" to isHeld))
                }
            }.onFailure { repository.logs.error("service.wake_lock_listener.failed", it) }
        }
        wakeLock?.acquire(30 * 60 * 1000L)
        promoted = true; acquiredAt = android.os.SystemClock.elapsedRealtime(); wakeSnapshot("acquire")
        repository.logs.event("service.foreground", BackgroundAccess.snapshot(this))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.getStringExtra("request")
        repository.logs.event("service.start", mapOf("startId" to startId, "restart" to (intent == null), "action" to intent?.action))
        if (intent?.action == "stop") {
            serviceScope.launch { repository.engine.stopAll(); repository.codexAccounts.cancelLogin(); stopIfIdle(this@TaskService) }
            return START_NOT_STICKY
        }
        try {
            dispatching = intent?.action == ScheduleRunner.ACTION_DUE
            promote()
            if (request != null) pending.remove(request)?.complete(Unit)
            if (intent?.action == ScheduleRunner.ACTION_DUE) serviceScope.launch {
                try { repository.scheduler.dispatchDue() }
                catch (e: Exception) { repository.logs.error("schedule.dispatch.failed", e) }
                finally { dispatching = false; stopIfIdle(this@TaskService) }
            }
            if (intent == null) serviceScope.launch { repository.engine.recover(); stopIfIdle(this@TaskService) }
        } catch (e: Exception) {
            repository.logs.error("service.promotion.failed", e)
            if (intent?.action == ScheduleRunner.ACTION_DUE) repository.scope.launch {
                repository.scheduler.blocked("前台任务服务未能启动，请检查通知与后台运行设置")
            }
            if (request != null) pending.remove(request)?.completeExceptionally(e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { repository.logs.event("service.task_removed", mapOf("active" to repository.state.value.hasBackgroundWork)); repository.logs.flush(); super.onTaskRemoved(rootIntent) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        repository.logs.event("service.destroyed", mapOf("active" to repository.state.value.hasBackgroundWork))
        repository.logs.flush(); serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        promoted = false; wakeSnapshot("release")
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        repository.logs.event("service.timeout", mapOf("startId" to startId, "type" to fgsType))
        serviceScope.launch { repository.engine.stopAll("系统停止了后台服务"); stopSelf() }
    }
    companion object {
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        suspend fun start(context: Context) {
            val id = newId()
            val ready = CompletableDeferred<Unit>()
            pending[id] = ready
            try {
                context.startForegroundService(Intent(context, TaskService::class.java).putExtra("request", id))
                check(withTimeoutOrNull(10_000) { ready.await(); true } == true) { "后台任务服务未能启动，请在设置中允许后台运行" }
            } finally { pending.remove(id) }
        }
        fun stopIfIdle(context: Context) {
            val state = (context.applicationContext as ChocolaApp).repository.state.value
            if (!state.hasBackgroundWork && pending.isEmpty()) context.stopService(Intent(context, TaskService::class.java))
        }
    }
}
