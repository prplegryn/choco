package app.chocola.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import app.chocola.data.TraceSpan

data class CommandResult(val output: String, val exitCode: Int, val durationMs: Long, val timedOut: Boolean = false)

/** Never block a coroutine in read() or waitFor(): descendants may keep stdout open. */
internal object ProcessRunner {
    suspend fun collect(process: Process, timeoutSeconds: Int?, trace: TraceSpan? = null, onOutput: suspend (String) -> Unit): CommandResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val counters = ProcessCounters()
        trace?.samples { counters.fields() + ("childAlive" to process.isAlive) }
        trace?.stage("polling")
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var published = 0L
        fun readAvailable() {
            // Bound each drain so an endlessly writing command remains cancellable.
            var drained = 0
            while (drained < 65_536) {
                val available = process.inputStream.available()
                if (available <= 0) break
                val count = process.inputStream.read(buffer, 0, minOf(available, buffer.size))
                if (count < 0) break
                drained += count
                counters.bytes += count
                counters.lastOutput = System.nanoTime()
                if (collected.size() + count > 200_000) {
                    val old = collected.toByteArray()
                    val keep = (200_000 - count).coerceAtLeast(0)
                    collected.reset()
                    collected.write(old, (old.size - keep).coerceAtLeast(0), minOf(old.size, keep))
                    counters.truncated = true
                }
                collected.write(buffer, 0, count)
                counters.retained = collected.size()
            }
        }
        suspend fun publish(text: String) {
            counters.consumer = System.nanoTime(); trace?.stage("output_consumer")
            try { onOutput(text) } finally {
                val duration = System.nanoTime() - counters.consumer
                counters.maxConsumer = maxOf(counters.maxConsumer, duration)
                trace?.timing("process.output_consumer", duration)
                counters.consumer = 0; trace?.stage("polling")
            }
        }
        try {
            suspend fun poll(): Int {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val pollAt = System.nanoTime()
                    if (counters.lastPoll != 0L) counters.maxPollGap = maxOf(counters.maxPollGap, pollAt - counters.lastPoll)
                    counters.lastPoll = pollAt; counters.polls++
                    trace?.stage("read_available")
                    readAvailable()
                    trace?.stage("polling")
                    val now = System.nanoTime()
                    if (now - published > 120_000_000 && collected.size() > 0) {
                        publish(collected.toString("UTF-8")); published = now
                    }
                    if (!process.isAlive && process.inputStream.available() == 0) return process.exitValue()
                    delay(if (process.isAlive) 35 else 1)
                }
            }
            val exit = if (timeoutSeconds == null) poll() else withTimeoutOrNull(timeoutSeconds.coerceIn(1, 600) * 1000L) { poll() }
            val timedOut = exit == null
            val text = collected.toString("UTF-8") + if (timedOut) "\n[命令超过 ${timeoutSeconds} 秒，已终止]" else ""
            publish(text)
            trace?.event("process.collected", counters.fields() + mapOf("exit" to (exit ?: 124), "timedOut" to timedOut))
            CommandResult(text, exit ?: 124, (System.nanoTime() - started) / 1_000_000, timedOut)
        } finally {
            trace?.stage("terminating")
            terminate(process)
        }
    }
    suspend fun terminate(process: Process) = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { process.destroy() }
        repeat(10) { if (process.isAlive) delay(20) }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
    }
}

internal class ProcessCounters {
    @Volatile var bytes = 0L
    @Volatile var retained = 0
    @Volatile var truncated = false
    @Volatile var polls = 0L
    @Volatile var lastPoll = 0L
    @Volatile var lastOutput = 0L
    @Volatile var maxPollGap = 0L
    @Volatile var consumer = 0L
    @Volatile var maxConsumer = 0L
    fun fields(now: Long = System.nanoTime()): Map<String, Any?> = mapOf("outputBytes" to bytes, "retainedBytes" to retained,
        "outputTruncated" to truncated, "polls" to polls, "sincePollMs" to lastPoll.takeIf { it > 0 }?.let { (now - it) / 1_000_000 },
        "sinceOutputMs" to lastOutput.takeIf { it > 0 }?.let { (now - it) / 1_000_000 }, "maxPollGapMs" to maxPollGap / 1_000_000,
        "consumerInFlightMs" to consumer.takeIf { it > 0 }?.let { (now - it) / 1_000_000 }, "maxConsumerMs" to maxConsumer / 1_000_000)
}
