package app.chocola.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

data class AnnotationPoint(val x: Float, val y: Float) {
    fun json() = JSONArray().put(x.toDouble()).put(y.toDouble())
    companion object { fun from(j: JSONArray) = AnnotationPoint(j.getDouble(0).toFloat(), j.getDouble(1).toFloat()) }
}
data class AnnotationMark(val id: String = newId(), val tool: String, val points: List<AnnotationPoint>, val color: Long = 0xFFE04444,
    val width: Float = .006f, val text: String = "", val textSize: Float = .045f) {
    fun json() = JSONObject().put("id", id).put("tool", tool).put("points", JSONArray(points.map { it.json() }))
        .put("color", color).put("width", width.toDouble()).put("text", text).put("textSize", textSize.toDouble())
    companion object { fun from(j: JSONObject) = AnnotationMark(j.getString("id"), j.getString("tool"),
        j.getJSONArray("points").let { a -> (0 until a.length()).map { AnnotationPoint.from(a.getJSONArray(it)) } },
        j.getLong("color"), j.getDouble("width").toFloat(), j.optString("text"), j.optDouble("textSize", .045).toFloat()) }
}
data class AnnotationAction(val kind: String, val mark: AnnotationMark? = null, val targets: List<String> = emptyList()) {
    fun json() = JSONObject().put("kind", kind).put("mark", mark?.json() ?: JSONObject.NULL).put("targets", JSONArray(targets))
    companion object { fun from(j: JSONObject) = AnnotationAction(j.getString("kind"), j.optJSONObject("mark")?.let(AnnotationMark::from), j.optJSONArray("targets")?.strings().orEmpty()) }
}
data class AnnotationHistory(val actions: List<AnnotationAction> = emptyList(), val cursor: Int = actions.size) {
    val canUndo get() = cursor > 0
    val canRedo get() = cursor < actions.size
    fun marks(): List<AnnotationMark> = buildList<AnnotationMark> {
        actions.take(cursor).forEach { action -> when (action.kind) {
            "add" -> action.mark?.let { add(it) }
            "remove" -> removeAll { it.id in action.targets }
            "clear" -> clear()
        } }
    }
    fun append(action: AnnotationAction) = AnnotationHistory(actions.take(cursor) + action)
    fun undo() = copy(cursor = (cursor - 1).coerceAtLeast(0))
    fun redo() = copy(cursor = (cursor + 1).coerceAtMost(actions.size))
    fun json() = JSONObject().put("actions", JSONArray(actions.map { it.json() })).put("cursor", cursor)
    companion object { fun from(j: JSONObject): AnnotationHistory {
        val actions = j.getJSONArray("actions").objects().map(AnnotationAction::from)
        return AnnotationHistory(actions, j.optInt("cursor", actions.size).coerceIn(0, actions.size))
    } }
}

class AnnotationSession internal constructor(private val file: File, private val scope: CoroutineScope, initial: AnnotationHistory,
    private val log: (String, Map<String, Any?>) -> Unit) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<AnnotationHistory> = mutable
    @Volatile private var closed = false
    fun append(action: AnnotationAction) { if (!closed) { mutable.update { it.append(action) }; schedule() } }
    fun undo() { if (!closed) { mutable.update { it.undo() }; schedule() } }
    fun redo() { if (!closed) { mutable.update { it.redo() }; schedule() } }
    private fun schedule() { scope.launch(Dispatchers.IO) { try { flush() } catch (e: Exception) { log("save.failed", mapOf("type" to e.javaClass.simpleName)) } } }
    suspend fun flush() = withContext(Dispatchers.IO + NonCancellable) { mutex.withLock {
        if (closed) return@withLock
        val bytes = mutable.value.json().toString().toByteArray()
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".part")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        log("saved", mapOf("actions" to mutable.value.actions.size, "cursor" to mutable.value.cursor, "bytes" to bytes.size))
    } }
    internal suspend fun close(delete: Boolean) = withContext(Dispatchers.IO + NonCancellable) { mutex.withLock { closed = true; if (delete) file.delete() } }
}

class AnnotationStore(private val root: File, private val scope: CoroutineScope, private val log: (String, Map<String, Any?>) -> Unit = { _, _ -> }) {
    private val lock = Mutex()
    private val sessions = ConcurrentHashMap<String, AnnotationSession>()
    private fun file(id: String): File { require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "标注记录无效" }; return File(root, "$id.json") }
    suspend fun open(id: String): AnnotationSession = withContext(Dispatchers.IO) { lock.withLock {
        sessions[id] ?: run {
            val file = file(id)
            require(!file.exists() || file.length() <= 32L * 1024 * 1024) { "标注记录过大" }
            val initial = if (file.exists()) AnnotationHistory.from(JSONObject(file.readText())) else AnnotationHistory()
            AnnotationSession(file, scope, initial, log).also { sessions[id] = it }
        }
    } }
    suspend fun discard(id: String): Unit = withContext(Dispatchers.IO) { lock.withLock {
        sessions.remove(id)?.close(delete = true) ?: file(id).delete()
        Unit
    } }
    suspend fun flush() { sessions.values.toList().forEach { it.flush() } }
    suspend fun reload() { lock.withLock { sessions.values.forEach { it.close(delete = false) }; sessions.clear() } }
}
