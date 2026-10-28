package app.chocola.data

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

class TraceSpan internal constructor(private val logs: Diagnostics, val kind: String, val id: String, val session: String,
    internal val started: Long, fields: Map<String, Any?>) : AutoCloseable {
    val link = mapOf("span" to id, "kind" to kind, "session" to session)
    internal val category = fields["category"]?.toString() ?: kind
    private val closed = AtomicBoolean()
    @Volatile private var stage = "starting"
    @Volatile private var changed = started
    @Volatile private var details: Map<String, Any?> = fields
    @Volatile private var sampler: (() -> Map<String, Any?>)? = null
    fun samples(value: () -> Map<String, Any?>) { sampler = value }
    fun stage(value: String, fields: Map<String, Any?> = emptyMap(), record: Boolean = false) {
        if (closed.get()) return
        val elapsed = SystemClock.elapsedRealtime() - changed
        if (stage != value) { stage = value; changed = SystemClock.elapsedRealtime() }
        if (fields.isNotEmpty()) details = details + fields
        if (record) logs.event("$kind.stage", link + fields + mapOf("stage" to value, "previousStageMs" to elapsed))
    }
    fun event(name: String, fields: Map<String, Any?> = emptyMap()) { logs.event(name, link + fields) }
    fun timing(name: String, nanos: Long) { logs.timing(name, nanos) }
    internal fun snapshot(): Map<String, Any?> {
        val now = SystemClock.elapsedRealtime()
        return link + details + runCatching { sampler?.invoke().orEmpty() }.getOrDefault(emptyMap()) +
            mapOf("stage" to stage, "ageMs" to (now - started), "stageAgeMs" to (now - changed))
    }
    fun finish(outcome: String = "done", fields: Map<String, Any?> = emptyMap()) {
        if (!closed.compareAndSet(false, true)) return
        logs.finished(this)
        logs.event("$kind.end", details + runCatching { sampler?.invoke().orEmpty() }.getOrDefault(emptyMap()) + link + fields +
            mapOf("outcome" to outcome, "durationMs" to (SystemClock.elapsedRealtime() - started), "lastStage" to stage,
                "lastStageMs" to (SystemClock.elapsedRealtime() - changed)))
    }
    override fun close() = finish()
}

/** A small upper-bound histogram, shared by database, parsing and layout samples. */
internal class DurationStats {
    private val limits = longArrayOf(100_000, 250_000, 500_000, 1_000_000, 2_000_000, 4_000_000, 8_000_000, 16_000_000,
        32_000_000, 64_000_000, 128_000_000, 256_000_000, 512_000_000, 1_024_000_000, 2_048_000_000, 4_096_000_000, Long.MAX_VALUE)
    private val buckets = LongArray(limits.size)
    private var count = 0L; private var total = 0L; private var max = 0L
    fun add(nanos: Long) { val value = nanos.coerceAtLeast(0); count++; total += value; max = maxOf(max, value); buckets[limits.indexOfFirst { value <= it }]++ }
    fun fields(): Map<String, Any> {
        fun percentile(fraction: Double): Double {
            var consumed = 0L
            val index = buckets.indexOfFirst { consumed += it; consumed >= kotlin.math.ceil(count * fraction).toLong().coerceAtLeast(1) }
            return minOf(limits[index.coerceAtLeast(0)], max) / 1_000_000.0
        }
        return mapOf("count" to count, "averageMs" to if (count == 0L) 0.0 else total / count / 1_000_000.0,
            "maxMs" to max / 1_000_000.0, "p50UpperMs" to percentile(.5), "p95UpperMs" to percentile(.95), "p99UpperMs" to percentile(.99))
    }
}

internal class TraceTimings(private val logs: Diagnostics) {
    private val samples = LinkedHashMap<String, DurationStats>()
    @Synchronized fun record(name: String, nanos: Long) {
        if (samples.size >= 128 && name !in samples) return
        samples.getOrPut(name) { DurationStats() }.add(nanos)
    }
    fun flush() {
        val copy = synchronized(this) { samples.toMap().also { samples.clear() } }
        copy.forEach { (name, stats) -> logs.event("performance.summary", stats.fields() + ("component" to name)) }
    }
}
