package app.chocola.data

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.chocola.agent.BackgroundAccess
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Observes work without acquiring a wake lock, restarting a request or changing power policy. */
internal class RuntimeProbe(private val context: Context, private val logs: Diagnostics) {
    private val thread = HandlerThread("choco-runtime-probe", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private data class Ping(val mono: Long, val uptime: Long, val visible: Boolean)
    private val pending = AtomicReference<Ping?>()
    private var tasks: Map<String, String> = emptyMap()
    private var processes = 0
    private var operations = 0
    private var visible = false
    private var running = false
    private var previous: TraceStamp? = null
    private var previousCpu = 0L
    private var expected = 2000L
    private var lastSample = 0L
    private var lastStack = 0L
    private var lastSnapshotMs = 0L
    private val power by lazy { context.getSystemService(PowerManager::class.java) }
    private val network by lazy { context.getSystemService(ConnectivityManager::class.java) }
    init { handler.post { registerSignals(); snapshotNow("initial") } }
    fun visible(value: Boolean) { handler.post { visible = value; update(); snapshotNow(if (value) "foreground" else "background") } }
    fun work(value: Map<String, String>, count: Int) { handler.post { tasks = value; processes = count; update() } }
    fun operations(count: Int) { handler.post { operations = count; update() } }
    fun snapshot(reason: String) { handler.post { snapshotNow(reason) } }
    private fun update() {
        val active = visible || tasks.isNotEmpty() || processes > 0 || operations > 0
        if (active && !running) { running = true; previous = null; handler.post(tick) }
        else if (!active && running) {
            running = false; handler.removeCallbacks(tick); pending.set(null); previous = null
            logs.event("runtime.monitor_idle")
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val now = logs.stamp()
                val cpu = android.os.Process.getElapsedCpuTime()
                previous?.let { before ->
                    val gap = ClockGap.between(before, now, expected, cpu - previousCpu)
                    if (gap.lateMs > 1500 || gap.sleepMs > 1500) logs.event("runtime.scheduler_gap", mapOf(
                        "elapsedGapMs" to gap.elapsedMs, "awakeGapMs" to gap.awakeMs, "sleepGapMs" to gap.sleepMs,
                        "lateAwakeMs" to gap.lateMs, "processCpuDeltaMs" to gap.cpuMs, "expectedMs" to expected,
                        "lastSnapshotWorkMs" to lastSnapshotMs, "previousMonoMs" to before.monoMs,
                        "wallClockShiftMs" to (now.wallMs - before.wallMs - gap.elapsedMs), "active" to logs.activeSnapshot(), "threads" to threadSummary()))
                }
                previous = now; previousCpu = cpu
                val ping = pending.get()
                if (ping == null) {
                    val next = Ping(now.monoMs, now.uptimeMs, visible)
                    pending.set(next)
                    main.post {
                        if (pending.compareAndSet(next, null)) {
                            val age = (SystemClock.uptimeMillis() - next.uptime).coerceAtLeast(0)
                            logs.timing("main.handler_round_trip", age * 1_000_000)
                            if (age >= 100) logs.event("runtime.main_ack_late", mapOf("awakeLagMs" to age,
                                "elapsedLagMs" to SystemClock.elapsedRealtime() - next.mono, "sentVisible" to next.visible))
                        }
                    }
                } else if (now.uptimeMs - ping.uptime > 1500 && now.monoMs - lastStack > 30_000) {
                    lastStack = now.monoMs
                    val ui = Looper.getMainLooper().thread
                    logs.event("runtime.main_stall", mapOf("awakeLagMs" to now.uptimeMs - ping.uptime,
                        "elapsedLagMs" to now.monoMs - ping.mono, "state" to ui.state.name,
                        "stack" to ui.stackTrace.take(32).joinToString("\n"), "active" to logs.activeSnapshot()))
                }
                if (now.monoMs - lastSample >= if (tasks.isNotEmpty() || operations > 0) 10_000 else 30_000) {
                    lastSample = now.monoMs; snapshotNow("periodic")
                }
            } catch (e: Exception) { logs.error("runtime.probe.failed", e) }
            finally {
                expected = when { visible -> 2000L; tasks.isNotEmpty() || operations > 0 -> 5000L; else -> 15_000L }
                if (running) handler.postDelayed(this, expected)
            }
        }
    }
    private fun snapshotNow(reason: String) {
        val started = SystemClock.elapsedRealtime()
        runCatching {
            val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            val runtime = Runtime.getRuntime()
            val status = runCatching { File("/proc/self/status").useLines { rows -> rows.filter {
                it.substringBefore(':') in setOf("State", "Threads", "VmRSS", "VmHWM", "VmSwap", "voluntary_ctxt_switches", "nonvoluntary_ctxt_switches")
            }.associate { it.substringBefore(':') to it.substringAfter(':').trim() } } }.getOrDefault(emptyMap())
            val caps = network.getNetworkCapabilities(network.activeNetwork)
            val data = BackgroundAccess.snapshot(context) + mapOf("reason" to reason, "activeTasks" to tasks.keys.sorted(),
                "taskPhases" to tasks, "processes" to processes, "operations" to operations, "service" to logs.serviceState, "active" to logs.activeSnapshot(),
                "importance" to importance.importance, "importanceReason" to importance.importanceReasonCode,
                "lastTrimLevel" to importance.lastTrimLevel, "lru" to importance.lru,
                "oomScoreAdj" to runCatching { File("/proc/self/oom_score_adj").readText().trim() }.getOrNull(),
                "cgroup" to runCatching { File("/proc/self/cgroup").readText().take(2048) }.getOrNull(),
                "proc" to status, "processCpuMs" to android.os.Process.getElapsedCpuTime(), "diagnostics" to logs.health(),
                "gc" to Debug.getRuntimeStats().filterKeys { it in setOf("art.gc.gc-count", "art.gc.gc-time", "art.gc.blocking-gc-count", "art.gc.blocking-gc-time", "art.gc.bytes-allocated", "art.gc.bytes-freed") },
                "javaHeapUsedBytes" to runtime.totalMemory() - runtime.freeMemory(), "javaHeapLimitBytes" to runtime.maxMemory(),
                "nativeHeapAllocatedBytes" to Debug.getNativeHeapAllocatedSize(), "thermalStatus" to power.currentThermalStatus,
                "interactive" to power.isInteractive, "powerSave" to power.isPowerSaveMode, "deviceIdle" to power.isDeviceIdleMode,
                "lowPowerStandbyEnabled" to if (Build.VERSION.SDK_INT >= 33) power.isLowPowerStandbyEnabled else null,
                "network" to networkFields(caps), "restrictBackgroundStatus" to network.restrictBackgroundStatus,
                "animatorScale" to Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
                "transitionScale" to Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f))
            logs.event("runtime.sample", data)
            if (reason == "memory_pressure" || reason == "initial") {
                val info = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                logs.event("runtime.memory", mapOf("availableBytes" to info.availMem, "totalBytes" to info.totalMem, "lowMemory" to info.lowMemory, "internalDiskUsableBytes" to context.filesDir.usableSpace,
                    "gc" to Debug.getRuntimeStats().filterKeys { it in setOf("art.gc.gc-count", "art.gc.gc-time", "art.gc.blocking-gc-count", "art.gc.blocking-gc-time", "art.gc.bytes-allocated", "art.gc.bytes-freed") }))
            }
        }.onFailure { logs.error("runtime.snapshot.failed", it, mapOf("reason" to reason)) }
        lastSnapshotMs = SystemClock.elapsedRealtime() - started
        logs.timing("runtime.snapshot", lastSnapshotMs * 1_000_000)
    }
    /** Read only our own scheduling counters, and only after an observed scheduling gap. */
    private fun threadSummary(): List<Map<String, Any?>> = runCatching {
        val pid = android.os.Process.myPid().toString()
        File("/proc/self/task").listFiles().orEmpty().sortedBy { if (it.name == pid) 0 else 1 }.asSequence().mapNotNull { dir ->
            val name = runCatching { File(dir, "comm").readText().trim() }.getOrNull() ?: return@mapNotNull null
            if (dir.name != pid && !listOf("RenderThread", "DefaultDispatch", "OkHttp", "choco").any { name.startsWith(it) }) return@mapNotNull null
            mapOf("tid" to dir.name, "name" to name,
                "schedstat" to runCatching { File(dir, "schedstat").readText().trim().take(256) }.getOrNull(),
                "waitChannel" to runCatching { File(dir, "wchan").readText().trim().take(128) }.getOrNull())
        }.take(24).toList()
    }.getOrDefault(emptyList())
    private fun networkFields(caps: NetworkCapabilities?): Map<String, Any?> = mapOf(
        "connected" to (caps != null), "wifi" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true),
        "cellular" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true), "vpn" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true),
        "validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
        "notSuspended" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) == true),
        "notMetered" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true))
    private fun registerSignals() {
        runCatching {
            val filter = IntentFilter().apply {
                listOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT, Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY,
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED).forEach(::addAction)
            }
            ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { logs.event("system.signal", mapOf("action" to intent.action)); snapshot(intent.action ?: "broadcast") }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            network.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(value: Network) { logs.event("network.available", mapOf("networkId" to value.toString())) }
                override fun onLost(value: Network) { logs.event("network.lost", mapOf("networkId" to value.toString())) }
                override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) { logs.event("network.capabilities", networkFields(caps) + ("networkId" to value.toString())) }
                override fun onBlockedStatusChanged(value: Network, blocked: Boolean) { logs.event("network.blocked", mapOf("networkId" to value.toString(), "blocked" to blocked)) }
            }, handler)
            power.addThermalStatusListener({ command -> handler.post(command) }) { status -> logs.event("system.thermal", mapOf("status" to status)) }
        }.onFailure { logs.error("runtime.signals.failed", it) }
    }
}
