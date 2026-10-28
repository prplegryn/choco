package app.chocola.data

import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.time.Instant
import java.util.ArrayDeque

internal data class TraceStamp(val wallMs: Long, val monoMs: Long, val uptimeMs: Long, val tid: Int, val thread: String)
internal data class TraceRecord(val sequence: Long, val run: String, val build: Int, val pid: Int, val stamp: TraceStamp,
    val event: String, val page: String, val foreground: Boolean, val fields: Map<String, Any?>) {
    fun json(written: TraceStamp): JSONObject = TraceValues().objectValue(fields, 0).also { json ->
        json.put("schema", 2).put("seq", sequence).put("runId", run).put("build", build).put("pid", pid)
            .put("time", Instant.ofEpochMilli(stamp.wallMs).toString()).put("monoMs", stamp.monoMs).put("uptimeMs", stamp.uptimeMs)
            .put("tid", stamp.tid).put("threadName", stamp.thread).put("event", event).put("pageAtEvent", page).put("foreground", foreground)
            .put("writeLagMs", (written.monoMs - stamp.monoMs).coerceAtLeast(0))
            .put("writeAwakeLagMs", (written.uptimeMs - stamp.uptimeMs).coerceAtLeast(0))
    }
}

/** Bad values, cycles or oversized fields must not disable the entire logging worker. */
internal class TraceValues {
    private var nodes = 512
    private var characters = 32_768
    private fun text(value: String): String {
        val limit = minOf(24_000, characters).coerceAtLeast(0)
        val result = value.take(limit)
        characters -= result.length
        return if (value.length > limit) "$result[truncated]" else result
    }
    fun objectValue(values: Map<*, *>, depth: Int): JSONObject = JSONObject().also { result ->
        values.entries.take(64).forEach { (key, child) ->
            if (nodes > 0) result.put(text(key?.toString().orEmpty().take(128)), value(child, depth + 1))
        }
    }
    private fun value(child: Any?, depth: Int): Any {
        if (--nodes < 0 || depth > 8) return "[depth or size limit]"
        return when (child) {
            null, JSONObject.NULL -> JSONObject.NULL
            is String -> text(child)
            is Double -> if (child.isFinite()) child else JSONObject.NULL
            is Float -> if (child.isFinite()) child else JSONObject.NULL
            is Number, is Boolean -> child
            is Map<*, *> -> objectValue(child, depth)
            is JSONObject -> objectValue(child.keys().asSequence().take(64).associateWith { child.opt(it) }, depth)
            is JSONArray -> JSONArray().also { out -> for (i in 0 until minOf(64, child.length())) out.put(value(child.opt(i), depth + 1)) }
            is Collection<*> -> JSONArray().also { out -> child.take(64).forEach { out.put(value(it, depth + 1)) } }
            is Array<*> -> JSONArray().also { out -> child.take(64).forEach { out.put(value(it, depth + 1)) } }
            else -> text(child.javaClass.simpleName)
        }
    }
}

/** Never allow slow storage or the public mirror to create an unbounded heap queue. */
internal class TraceBuffer(private val capacity: Int = 1536) {
    init { require(capacity > 0) }
    private val records = ArrayDeque<TraceRecord>()
    private var dropped = 0L
    @Synchronized fun offer(record: TraceRecord) { if (records.size == capacity) { records.removeFirst(); dropped++ }; records.addLast(record) }
    @Synchronized fun drain(limit: Int = 128): List<TraceRecord> = buildList { repeat(minOf(limit, records.size)) { add(records.removeFirst()) } }
    @Synchronized fun health(): Pair<Int, Long> = records.size to dropped
    @Synchronized fun recent(): List<Map<String, Any?>> = records.toList().takeLast(12).map {
        mapOf("seq" to it.sequence, "event" to it.event, "monoMs" to it.stamp.monoMs, "thread" to it.stamp.thread)
    }
}

internal data class JournalCursor(val generation: Long, val offset: Long)
internal class JournalSlice(val reset: Boolean, val cursor: JournalCursor, private val files: List<Pair<InputStream, Long>>) : AutoCloseable {
    fun copyTo(out: OutputStream) {
        val buffer = ByteArray(32_768)
        files.forEach { (input, size) ->
            var remaining = size
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw EOFException("Diagnostic segment changed")
                out.write(buffer, 0, count); remaining -= count
            }
        }
    }
    override fun close() { files.forEach { runCatching { it.first.close() } } }
}

/** File descriptors are captured under the lock; MediaStore copying happens outside it. */
internal class TraceJournal(val directory: File, private val segmentBytes: Long = 2_097_152, private val archives: Int = 6) {
    init { require(segmentBytes > 0 && archives > 0) }
    private val current = File(directory, "current.jsonl")
    private var generation = 0L
    private var prepared = false
    @Synchronized private fun prepare() {
        if (prepared) return
        check(directory.isDirectory || directory.mkdirs())
        if (current.isFile && current.length() > 0) RandomAccessFile(current, "rw").use { file ->
            file.seek(file.length() - 1)
            if (file.read() != 10) {
                val tail = ByteArray(8192)
                var end = file.length()
                var keep = 0L
                while (end > 0) {
                    val start = (end - tail.size).coerceAtLeast(0)
                    val count = (end - start).toInt()
                    file.seek(start); file.readFully(tail, 0, count)
                    val newline = (count - 1 downTo 0).firstOrNull { tail[it] == 10.toByte() }
                    if (newline != null) { keep = start + newline + 1; break }
                    end = start
                }
                file.setLength(keep); generation++
            }
        }
        prepared = true
    }
    @Synchronized fun append(lines: List<String>, sync: Boolean = false) {
        if (lines.isEmpty()) return
        prepare()
        var output: FileOutputStream? = null
        try {
            for (line in lines) {
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                require(bytes.size <= 262_144) { "Diagnostic record exceeds the segment record limit" }
                if (current.length() > 0 && current.length() + bytes.size > segmentBytes) {
                    output?.let { if (sync) it.fd.sync(); it.close() }; output = null
                    rotate()
                }
                val sink = output ?: FileOutputStream(current, true).also { output = it }
                sink.write(bytes)
            }
            if (sync) output?.fd?.sync()
        } catch (e: Exception) { prepared = false; throw e }
        finally { output?.close() }
    }
    private fun rotate() {
            File(directory, "archive-$archives.jsonl").delete()
            for (i in archives - 1 downTo 1) {
                val old = File(directory, "archive-$i.jsonl")
                if (old.exists()) check(old.renameTo(File(directory, "archive-${i + 1}.jsonl")))
            }
            check(current.renameTo(File(directory, "archive-1.jsonl"))); generation++
    }
    fun emergency(line: String) {
        directory.mkdirs()
        val file = File(directory, "fatal.jsonl")
        if (file.length() > 262_144) file.delete()
        FileOutputStream(file, true).use { out -> out.write((line + "\n").toByteArray(Charsets.UTF_8)); out.fd.sync() }
    }
    @Synchronized fun snapshot(previous: JournalCursor?): JournalSlice? {
        prepare()
        if (!current.exists()) return null
        val size = current.length()
        val reset = previous == null || previous.generation != generation || previous.offset > size
        if (!reset && previous?.offset == size) return null
        val streams = mutableListOf<Pair<InputStream, Long>>()
        try {
            if (reset) {
                val files = (archives downTo 1).map { File(directory, "archive-$it.jsonl") } + File(directory, "fatal.jsonl") + current
                files.filter { it.isFile }.forEach { streams += it.inputStream() to it.length() }
            } else {
                val input = current.inputStream(); input.channel.position(requireNotNull(previous).offset)
                streams += input to (size - previous.offset)
            }
            return JournalSlice(reset, JournalCursor(generation, size), streams)
        } catch (e: Exception) { streams.forEach { it.first.close() }; throw e }
    }
}

internal data class ClockGap(val elapsedMs: Long, val awakeMs: Long, val sleepMs: Long, val lateMs: Long, val cpuMs: Long) {
    companion object {
        fun between(before: TraceStamp, after: TraceStamp, expectedMs: Long, cpuDeltaMs: Long): ClockGap {
            val elapsed = (after.monoMs - before.monoMs).coerceAtLeast(0)
            val awake = (after.uptimeMs - before.uptimeMs).coerceAtLeast(0)
            return ClockGap(elapsed, awake, (elapsed - awake).coerceAtLeast(0), (awake - expectedMs).coerceAtLeast(0), cpuDeltaMs.coerceAtLeast(0))
        }
    }
}
