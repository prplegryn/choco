package app.chocola.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.app.ActivityManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import app.chocola.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Capture clocks on the caller; disk and public mirroring have independent workers. */
class Diagnostics(private val context: Context) {
    @Volatile var screen: String = "chat"
    @Volatile var currentSession: String = ""
    @Volatile var foreground: Boolean = false
        private set
    @Volatile var motionLabel: String = ""
        private set
    @Volatile var serviceState: Map<String, Any?> = emptyMap()
    val directory = File(context.filesDir, "diagnostics")
    private val journal = TraceJournal(directory)
    private val buffer = TraceBuffer()
    private val run = newId()
    private val sequence = AtomicLong()
    private val writeScheduled = AtomicBoolean()
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "choco-log-writer").apply { isDaemon = true } }
    private val mirrorWorker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "choco-log-mirror").apply { isDaemon = true } }
    private val spans = ConcurrentHashMap<String, TraceSpan>()
    private val monitoredKinds = setOf("task", "api", "net", "tool", "context")
    private val timing = TraceTimings(this)
    private val probe by lazy { RuntimeProbe(context, this) }
    private var mirrorCursor: JournalCursor? = null
    private var publicUri: Uri? = null
    @Volatile private var writeFailures = 0L
    @Volatile private var lastWrite = 0L
    @Volatile private var mirrorStarted = 0L
    @Volatile private var mirrorSucceeded = 0L
    private var lastHealth = 0L
    private var oldDropped = 0L
    private var pendingRecords: List<TraceRecord> = emptyList()
    private var lastMirrorError = 0L
    private val mirrorKey = "mirror-${BuildConfig.VERSION_CODE}"
    init {
        event("app.start", mapOf("version" to BuildConfig.VERSION_NAME, "revision" to BuildConfig.SOURCE_REVISION,
            "sdk" to Build.VERSION.SDK_INT, "device" to Build.MODEL, "manufacturer" to Build.MANUFACTURER,
            "osRelease" to Build.VERSION.RELEASE, "osIncremental" to Build.VERSION.INCREMENTAL, "abi" to Build.SUPPORTED_ABIS.joinToString(),
            "logSegmentBytes" to 2_097_152, "logArchives" to 6, "logQueueCapacity" to 1536))
        mirrorWorker.scheduleWithFixedDelay({ mirror() }, 3, 15, TimeUnit.SECONDS)
        writer.execute { previousExits() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { runCatching {
                val record = capture("fatal", mapOf("failedThread" to thread.name, "error" to error.javaClass.name,
                    "message" to error.message, "stack" to error.stackTraceToString().take(24_000),
                    "active" to activeSnapshot(), "recentEvents" to buffer.recent()))
                journal.emergency(Redactor.json(record.json(stamp())).toString())
            } } finally { previous?.uncaughtException(thread, error) }
        }
    }
    internal fun stamp() = TraceStamp(System.currentTimeMillis(), SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), android.os.Process.myTid(), Thread.currentThread().name)
    private fun capture(name: String, fields: Map<String, Any?>): TraceRecord = TraceRecord(sequence.incrementAndGet(), run, BuildConfig.VERSION_CODE,
        android.os.Process.myPid(), stamp(), name, screen, foreground, fields.toMap())
    fun event(name: String, fields: Map<String, Any?> = emptyMap()) { buffer.offer(capture(name, fields)); scheduleDrain() }
    private fun scheduleDrain(delayMs: Long = 250) {
        if (!writeScheduled.compareAndSet(false, true)) return
        writer.schedule({
            try { drain() } finally {
                writeScheduled.set(false)
                if (buffer.health().first > 0 || pendingRecords.isNotEmpty()) scheduleDrain(if (pendingRecords.isEmpty()) 250 else 2000)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }
    fun error(name: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        event(name, fields + mapOf("error" to error.javaClass.name, "message" to error.message, "stack" to error.stackTraceToString().take(24_000)))
    }
    fun visible(value: Boolean) { foreground = value; probe.visible(value); event("runtime.visibility", mapOf("visible" to value)) }
    fun work(tasks: Map<String, String>, processes: Int) { probe.work(tasks, processes) }
    fun systemSnapshot(reason: String) { probe.snapshot(reason) }
    fun trimMemory(level: Int) { event("runtime.memory_pressure", mapOf("level" to level)); probe.snapshot("memory_pressure") }
    fun begin(kind: String, id: String = newId(), session: String = currentSession, fields: Map<String, Any?> = emptyMap()): TraceSpan {
        val span = TraceSpan(this, kind, id, session, SystemClock.elapsedRealtime(), fields)
        if (spans.size >= 128) spans.values.minByOrNull { it.started }?.finish("trace_capacity")
        spans["$kind:$id"] = span
        if (kind == "motion") refreshMotions()
        else if (kind in monitoredKinds) refreshOperations()
        event("$kind.begin", span.link + fields)
        return span
    }
    internal fun finished(span: TraceSpan) {
        spans.remove("${span.kind}:${span.id}", span)
        if (span.kind == "motion") refreshMotions() else if (span.kind in monitoredKinds) refreshOperations()
    }
    private fun refreshOperations() { probe.operations(spans.values.count { it.kind in monitoredKinds }) }
    private fun refreshMotions() { motionLabel = spans.values.filter { it.kind == "motion" }.sortedBy { it.started }.take(8).joinToString(",") { "${it.category}:${it.id.take(8)}" } }
    internal fun activeSnapshot(): List<Map<String, Any?>> = spans.values.sortedBy { it.started }.take(24).map { it.snapshot() }
    fun timing(name: String, nanos: Long) { timing.record(name, nanos) }
    internal fun health(): Map<String, Any?> {
        val (queued, dropped) = buffer.health()
        val now = SystemClock.elapsedRealtime()
        return mapOf("queued" to queued, "dropped" to dropped, "writeFailures" to writeFailures,
            "sinceWriteMs" to lastWrite.takeIf { it > 0 }?.let { now - it },
            "mirrorInFlightMs" to mirrorStarted.takeIf { it > 0 }?.let { now - it },
            "sinceMirrorSuccessMs" to mirrorSucceeded.takeIf { it > 0 }?.let { now - it })
    }
    fun flush() { writer.execute { drain(); mirrorWorker.execute { mirror() } } }
    private fun drain() {
        val started = System.nanoTime()
        try {
            val records = pendingRecords.ifEmpty { buffer.drain() }
            if (records.isNotEmpty()) {
                pendingRecords = records
                val written = stamp()
                journal.append(records.map { record ->
                    runCatching { Redactor.json(record.json(written)).toString() }.getOrElse {
                        record.copy(fields = mapOf("serializationError" to it.javaClass.simpleName)).json(written).toString()
                    }
                })
                pendingRecords = emptyList()
                lastWrite = SystemClock.elapsedRealtime()
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastHealth >= 30_000) {
                lastHealth = now; timing.flush()
                val (queued, dropped) = buffer.health()
                if (dropped != oldDropped || writeFailures > 0 || queued > 512) {
                    event("diagnostics.health", mapOf("queued" to queued, "dropped" to dropped, "writeFailures" to writeFailures)); oldDropped = dropped
                }
            }
        } catch (_: Exception) { writeFailures++ }
        finally { timing.record("diagnostics.serialize_write", System.nanoTime() - started) }
    }
    private fun previousExits() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
            var newest = preferences.getLong("lastExit", 0)
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .filter { it.timestamp > newest }.sortedBy { it.timestamp }.forEach { exit ->
                    val trace = if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR)
                        runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readTextBounded(20_000) } }.getOrNull() else null
                    // Native tombstones are protobuf, not UTF-8; never decode arbitrary binary as log text.
                    val nativeTraceAvailable = if (exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE)
                        runCatching { exit.traceInputStream?.use { true } ?: false }.getOrNull() else null
                    event("app.previous_exit", mapOf("exitPid" to exit.pid, "process" to exit.processName, "at" to exit.timestamp,
                        "reason" to exit.reason, "status" to exit.status, "importance" to exit.importance, "description" to exit.description,
                        "rssKb" to exit.rss, "pssKb" to exit.pss, "taskState" to exit.processStateSummary?.toString(Charsets.UTF_8),
                        "trace" to trace, "nativeTraceAvailable" to nativeTraceAvailable))
                    newest = maxOf(newest, exit.timestamp)
                }
            preferences.edit().putLong("lastExit", newest).apply()
        }.onFailure { error("app.exit_info.failed", it) }
    }
    @Synchronized private fun mirror() {
        val started = System.nanoTime()
        mirrorStarted = SystemClock.elapsedRealtime()
        runCatching {
            journal.snapshot(mirrorCursor)?.use { slice ->
                if (Build.VERSION.SDK_INT >= 29) {
                    val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
                    val resolver = context.contentResolver
                    val uri = publicUri ?: preferences.getString(mirrorKey, null)?.let(Uri::parse) ?: run {
                        val name = "chocola-${BuildConfig.VERSION_CODE}.jsonl"
                        val relative = Environment.DIRECTORY_DOWNLOADS + "/apksync/Chocola-logs/"
                        val existing = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(name, relative), null)?.use {
                            if (it.moveToFirst()) Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0).toString()) else null
                        }
                        existing ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.RELATIVE_PATH, relative); put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson")
                        })
                    } ?: throw java.io.IOException("Diagnostic mirror URI unavailable")
                    publicUri = uri; preferences.edit().putString(mirrorKey, uri.toString()).apply()
                    resolver.openOutputStream(uri, if (slice.reset) "wt" else "wa")?.use { slice.copyTo(it) }
                        ?: throw java.io.IOException("Diagnostic mirror output unavailable")
                } else {
                    val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "apksync/Chocola-logs/chocola-${BuildConfig.VERSION_CODE}.jsonl")
                    dest.parentFile?.mkdirs(); dest.outputStreamAppend(!slice.reset).use { slice.copyTo(it) }
                }
                mirrorCursor = slice.cursor
                mirrorSucceeded = SystemClock.elapsedRealtime()
            }
        }.onFailure {
            // Retry with an authoritative bounded snapshot, even if an append was partial.
            publicUri = null; mirrorCursor = null
            runCatching { context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().remove(mirrorKey).apply() }
            val now = SystemClock.elapsedRealtime()
            if (now - lastMirrorError >= 60_000 || lastMirrorError == 0L) {
                lastMirrorError = now
                event("diagnostics.mirror.failed", mapOf("error" to it.javaClass.simpleName))
            }
        }
        mirrorStarted = 0
        timing.record("diagnostics.public_mirror", System.nanoTime() - started)
    }
}

private fun java.io.Reader.readTextBounded(limit: Int): String {
    val chars = CharArray(limit); var count = 0
    while (count < limit) { val n = read(chars, count, limit - count); if (n < 0) break; count += n }
    return String(chars, 0, count)
}
private fun File.outputStreamAppend(append: Boolean) = java.io.FileOutputStream(this, append)

/** Read-only diagnostics, restricted by actual Binder UID/package, never by caller input. */
class DiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true
    private fun enforce() {
        val uid = Binder.getCallingUid()
        val ctx = requireNotNull(context)
        val packages = ctx.packageManager.getPackagesForUid(uid).orEmpty()
        if (uid != android.os.Process.myUid() && uid != 2000 && uid != 0 && !packages.contains("com.termux"))
            throw SecurityException("Diagnostics caller is not allowed")
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        enforce()
        return MatrixCursor(arrayOf("name", "size")).apply {
            File(requireNotNull(context).filesDir, "diagnostics").listFiles()?.filter { it.extension == "jsonl" }?.forEach { addRow(arrayOf(it.name, it.length())) }
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforce()
        require(mode == "r")
        val root = File(requireNotNull(context).filesDir, "diagnostics").canonicalFile
        val name = uri.lastPathSegment?.takeIf { it.matches(Regex("(?:current|fatal|archive-[1-6])\\.jsonl")) } ?: "current.jsonl"
        val file = File(root, name).canonicalFile
        require(file.parentFile == root)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String { enforce(); return "application/x-ndjson" }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read only")
}
