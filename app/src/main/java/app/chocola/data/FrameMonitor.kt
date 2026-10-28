package app.chocola.data

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window

/** Collection and aggregation run on a dedicated worker, never in Compose drawing. */
class FrameMonitor(private val window: Window, private val logs: Diagnostics, private val surface: String) {
    private val thread = HandlerThread("chocola-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private var attached = false
    private var closed = false
    private var scene = ""
    private var motions = ""
    private var started = SystemClock.elapsedRealtime()
    private var lastSlow = 0L
    private var suppressed = 0
    private var budget = 16_666_667L
    private var samples = FrameSamples()
    private val stages = LongArray(9)
    private val stageIds = intArrayOf(FrameMetrics.UNKNOWN_DELAY_DURATION, FrameMetrics.INPUT_HANDLING_DURATION,
        FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION,
        FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION, -1)
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
        val nextScene = logs.screen
        val nextMotions = logs.motionLabel
        if (scene != nextScene || motions != nextMotions) { emit(); scene = nextScene; motions = nextMotions }
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
        val allocated = if (deadline > 0) deadline else budget
        for (i in 0..7) stages[i] = metrics.getMetric(stageIds[i]).coerceAtLeast(0)
        stages[8] = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION).coerceAtLeast(0) else 0
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
            logs.event("ui.frame.first", mapOf("surface" to surface, "durationMs" to duration / 1_000_000.0,
                "budgetMs" to allocated / 1_000_000.0, "stagesMs" to stageFields(), "lostMetricCallbacks" to lost))
        } else if (duration > 0) {
            samples.add(duration, allocated, lost, stages)
            val now = SystemClock.elapsedRealtime()
            if (duration >= maxOf(32_000_000, allocated * 2)) {
                if (now - lastSlow >= 1000) {
                    logs.event("ui.frame.slow", mapOf("page" to scene, "surface" to surface, "motions" to motions,
                        "durationMs" to duration / 1_000_000.0, "budgetMs" to allocated / 1_000_000.0, "stagesMs" to stageFields(),
                        "vsyncDelayMs" to (metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP) - metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)).coerceAtLeast(0) / 1_000_000.0,
                        "callbackDelayMs" to (System.nanoTime() - metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)).coerceAtLeast(0) / 1_000_000.0,
                        "lostMetricCallbacks" to lost, "suppressedSlowEvents" to suppressed))
                    suppressed = 0; lastSlow = now
                } else suppressed++
            }
            if (now - started >= 4000) emit()
        }
    }
    private fun stageFields() = FrameSamples.StageNames.indices.associate { FrameSamples.StageNames[it] to stages[it] / 1_000_000.0 }
    fun start() {
        if (attached || closed) return
        val rate = window.decorView.display?.refreshRate?.takeIf { it > 0 } ?: 60f
        handler.post { budget = (1_000_000_000.0 / rate).toLong() }
        window.addOnFrameMetricsAvailableListener(listener, handler); attached = true
    }
    fun stop() {
        if (!attached) return
        window.removeOnFrameMetricsAvailableListener(listener); attached = false
        handler.post { emit() }
    }
    fun close() {
        if (closed) return
        stop(); closed = true
        handler.post { emit(); thread.quitSafely() }
    }
    private fun emit() {
        if (samples.count > 0) logs.event("ui.frames", samples.fields() + mapOf("page" to scene, "surface" to surface,
            "motions" to motions, "windowMs" to (SystemClock.elapsedRealtime() - started)))
        samples = FrameSamples(); started = SystemClock.elapsedRealtime()
    }
}

/** Per-frame deadlines matter on devices switching between 60, 90 and 120 Hz. */
internal class FrameSamples {
    companion object { val StageNames = arrayOf("unknownDelay", "input", "animation", "layout", "draw", "sync", "command", "swap", "gpu") }
    var count = 0
        private set
    private var total = 0L
    private var max = 0L
    private var overBudget = 0
    private var over32 = 0
    private var lostCallbacks = 0
    private var minBudget = Long.MAX_VALUE
    private var maxBudget = 0L
    private val buckets = IntArray(251)
    private val maxStages = LongArray(StageNames.size)
    fun add(nanos: Long, deadline: Long, lost: Int, stages: LongArray) {
        if (nanos <= 0 || deadline <= 0) return
        count++; total += nanos; max = maxOf(max, nanos)
        if (nanos > deadline) overBudget++
        if (nanos > 32_000_000) over32++
        lostCallbacks += lost.coerceAtLeast(0); minBudget = minOf(minBudget, deadline); maxBudget = maxOf(maxBudget, deadline)
        buckets[((nanos - 1) / 2_000_000).coerceIn(0, buckets.lastIndex.toLong()).toInt()]++
        maxStages.indices.forEach { maxStages[it] = maxOf(maxStages[it], stages.getOrElse(it) { 0 }) }
    }
    fun fields(): Map<String, Any> {
        fun percentile(fraction: Double): Double {
            if (count == 0) return 0.0
            var cumulative = 0
            val index = buckets.indexOfFirst { cumulative += it; cumulative >= kotlin.math.ceil(count * fraction).toInt() }
            return (if (index == buckets.lastIndex) max else minOf((index + 1) * 2_000_000L, max)) / 1_000_000.0
        }
        return mapOf("frames" to count, "overBudget" to overBudget, "over32ms" to over32,
            "averageMs" to if (count > 0) total / count / 1_000_000.0 else 0.0,
            "p50UpperMs" to percentile(.5), "p95UpperMs" to percentile(.95), "p99UpperMs" to percentile(.99), "maxMs" to max / 1_000_000.0,
            "minBudgetMs" to if (count > 0) minBudget / 1_000_000.0 else 0.0, "maxBudgetMs" to maxBudget / 1_000_000.0,
            "maxStagesMs" to StageNames.indices.associate { StageNames[it] to maxStages[it] / 1_000_000.0 }, "lostMetricCallbacks" to lostCallbacks)
    }
}
