package app.chocola.data

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class NotebookItem(val path: String, val name: String, val directory: Boolean, val modified: Long, val bytes: Long) {
    fun json(): JSONObject = JSONObject().put("path", path).put("name", name).put("directory", directory).put("modified", modified).put("bytes", bytes)
}
data class NotebookDocument(val path: String, val text: String, val revision: String)
data class NotebookDraft(val path: String, val text: String, val revision: String)
data class NotebookRevision(val id: String, val time: Long)
data class NotebookTrash(val id: String, val path: String, val time: Long)
class NotebookConflict : IllegalStateException("这份笔记已在别处修改，编辑草稿已保留")

/** Markdown stays portable; app-private recovery data is outside the visible tree. */
class NotebookStore(private val root: File, private val changed: () -> Unit = {}, private val log: (String, Map<String, Any>) -> Unit = { _, _ -> }) {
    companion object {
        const val MAX_BYTES = 2_000_000
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun name(raw: String, directory: Boolean): String {
            val value = raw.trim().let { if (!directory && !it.endsWith(".md", true)) "$it.md" else it }
            require(value.isNotBlank() && value !in setOf(".md", ".", "..") && !value.startsWith('.') && value.toByteArray().size <= 200 && value.none { it == '/' || it == '\\' || it.isISOControl() }) { "名称不能包含路径、控制字符或以点开头" }
            return value
        }
    }
    private val documents get() = File(root, "documents").apply {
        require(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(toPath())) { "笔记目录不能使用符号链接" }
        mkdirs()
    }.canonicalFile
    private fun id(path: String) = hash(path.toByteArray())
    private fun resolve(path: String, allowRoot: Boolean = false): File {
        require(!path.startsWith('/') && '\\' !in path && path.none(Char::isISOControl)) { "笔记路径无效" }
        val parts = path.split('/').filter { it.isNotEmpty() }
        require(parts.size <= 16 && parts.none { it == "." || it == ".." || it.startsWith('.') }) { "笔记路径无效" }
        require(allowRoot || parts.isNotEmpty()) { "请选择笔记或文件夹" }
        val base = documents.canonicalFile
        var file = base
        parts.forEach { part ->
            file = File(file, part)
            require(!Files.isSymbolicLink(file.toPath())) { "笔记不能使用符号链接" }
        }
        require(file.canonicalPath == base.path || file.canonicalPath.startsWith(base.path + File.separator)) { "路径超出笔记本" }
        return file
    }
    private fun relative(file: File) = file.relativeTo(documents).invariantSeparatorsPath
    private fun item(file: File) = NotebookItem(relative(file), file.name, file.isDirectory, file.lastModified(), if (file.isFile) file.length() else 0)
    private fun atomic(file: File, bytes: ByteArray) {
        val started = System.nanoTime()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.${newId()}.tmp")
        try {
            FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete(); log("atomic_write", mapOf("bytes" to bytes.size, "durationMs" to (System.nanoTime() - started) / 1_000_000.0)) }
    }
    @Synchronized fun list(path: String = "", query: String = ""): List<NotebookItem> {
        val base = resolve(path, true); require(base.isDirectory) { "文件夹不存在" }
        val needle = query.trim()
        val source = if (needle.isEmpty()) base.listFiles().orEmpty().asSequence() else base.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith('.') }.drop(1)
        return source.filter { !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) && (it.isDirectory || it.extension.equals("md", true)) }.take(10_000).filter { file ->
            needle.isBlank() || file.name.contains(needle, true) || (file.isFile && file.length() <= MAX_BYTES && file.readText().contains(needle, true))
        }.map(::item).sortedWith(compareBy<NotebookItem> { !it.directory }.thenByDescending { it.modified }.thenBy { it.name }).toList()
    }
    @Synchronized fun folders(): List<String> = listTree().filter { it.directory }.map { it.path }
    private fun listTree(): List<NotebookItem> = documents.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith('.') }.drop(1).take(10_000)
        .filter { !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith('.') }.map(::item).toList()
    @Synchronized fun read(path: String): NotebookDocument {
        val file = resolve(path)
        require(file.isFile && file.extension.equals("md", true) && file.length() <= MAX_BYTES) { "笔记不存在或超过 2 MB" }
        val bytes = file.readBytes()
        return NotebookDocument(relative(file), String(bytes, Charsets.UTF_8), hash(bytes))
    }
    @Synchronized fun create(parent: String, rawName: String, directory: Boolean, content: String = ""): NotebookItem {
        val base = resolve(parent, true); require(base.isDirectory) { "文件夹不存在" }
        val path = listOf(parent, name(rawName, directory)).filter(String::isNotBlank).joinToString("/")
        val file = resolve(path); require(!file.exists()) { "已存在同名项目" }
        require(listTree().size < 10_000) { "笔记项目过多" }
        if (directory) check(file.mkdir()) { "文件夹创建失败" } else {
            val bytes = content.toByteArray(); require(bytes.size <= MAX_BYTES) { "笔记不能超过 2 MB" }; atomic(file, bytes)
        }
        changed(); return item(file)
    }
    @Synchronized fun save(path: String, text: String, expectedRevision: String): NotebookDocument {
        val old = read(path)
        if (old.revision != expectedRevision) { log("conflict", mapOf("path" to path)); throw NotebookConflict() }
        if (old.text == text) return old
        val bytes = text.toByteArray(); require(bytes.size <= MAX_BYTES) { "笔记不能超过 2 MB" }
        val history = File(root, "history/${id(path)}").apply { mkdirs() }
        atomic(File(history, "${System.currentTimeMillis()}-${newId().take(8)}.md"), old.text.toByteArray())
        history.listFiles().orEmpty().sortedByDescending { it.name }.drop(20).forEach { it.delete() }
        atomic(resolve(path), bytes)
        changed(); return NotebookDocument(path, text, hash(bytes))
    }
    @Synchronized fun move(path: String, parent: String, rawName: String): NotebookItem {
        val source = resolve(path); require(source.exists()) { "项目已不存在" }
        val base = resolve(parent, true); require(base.isDirectory) { "目标文件夹不存在" }
        require(base != source && !base.path.startsWith(source.path + File.separator)) { "不能移入自身" }
        val targetPath = listOf(parent, name(rawName, source.isDirectory)).filter(String::isNotBlank).joinToString("/")
        if (targetPath == path) return item(source)
        val target = resolve(targetPath); require(!target.exists()) { "已存在同名项目" }
        // Drafts/revisions follow every descendant through folder moves.
        val notes = if (source.isDirectory) source.walkTopDown().filter { it.isFile && it.extension.equals("md", true) }.toList() else listOf(source)
        val mapping = notes.map { relative(it) to targetPath + relative(it).removePrefix(path) }
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        mapping.forEach { (old, next) ->
            val history = File(root, "history/${id(old)}")
            if (history.exists()) history.renameTo(File(root, "history/${id(next)}"))
            draft(old)?.let { value -> saveDraft(next, value.text, value.revision); clearDraft(old) }
        }
        changed(); return item(target)
    }
    @Synchronized fun trash(path: String): NotebookTrash {
        val source = resolve(path); require(source.exists()) { "项目已不存在" }
        val value = NotebookTrash(newId(), path, System.currentTimeMillis())
        val destination = File(root, "trash/${value.id}").apply { mkdirs() }
        atomic(File(destination, "meta.json"), JSONObject().put("path", path).put("time", value.time).toString().toByteArray())
        Files.move(source.toPath(), File(destination, "content").toPath(), StandardCopyOption.ATOMIC_MOVE)
        changed(); return value
    }
    @Synchronized fun trashList(): List<NotebookTrash> = File(root, "trash").listFiles().orEmpty().mapNotNull { folder ->
        runCatching { val j = JSONObject(File(folder, "meta.json").readText()); if (!File(folder, "content").exists()) null else NotebookTrash(folder.name, j.getString("path"), j.getLong("time")) }.getOrNull()
    }.sortedByDescending { it.time }
    @Synchronized fun restoreTrash(trashId: String) {
        val value = trashList().firstOrNull { it.id == trashId } ?: error("回收项目不存在")
        val target = resolve(value.path); require(!target.exists()) { "原位置已有同名项目，请先重命名" }
        require(target.parentFile?.isDirectory == true) { "请先恢复或重建原文件夹" }
        val folder = File(root, "trash/${value.id}")
        Files.move(File(folder, "content").toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        folder.deleteRecursively(); changed()
    }
    @Synchronized fun revisions(path: String): List<NotebookRevision> = File(root, "history/${id(path)}").listFiles().orEmpty().filter { it.extension == "md" }
        .sortedByDescending { it.name }.map { NotebookRevision(it.name, it.name.substringBefore('-').toLongOrNull() ?: it.lastModified()) }
    @Synchronized fun revision(path: String, revisionId: String): String {
        require(revisions(path).any { it.id == revisionId }) { "版本已不存在" }
        return File(root, "history/${id(path)}/$revisionId").readText()
    }
    @Synchronized fun saveDraft(path: String, text: String, revision: String) {
        resolve(path); require(text.toByteArray().size <= MAX_BYTES) { "笔记不能超过 2 MB" }
        atomic(File(root, "drafts/${id(path)}.json"), JSONObject().put("path", path).put("text", text).put("revision", revision).toString().toByteArray())
    }
    @Synchronized fun draft(path: String): NotebookDraft? = File(root, "drafts/${id(path)}.json").takeIf { it.isFile }?.let {
        val j = JSONObject(it.readText()); NotebookDraft(j.getString("path"), j.getString("text"), j.getString("revision"))
    }
    @Synchronized fun clearDraft(path: String) { File(root, "drafts/${id(path)}.json").delete() }
    @Synchronized fun export(path: String, cache: File): File = File(cache, "notes/${newId()}/${resolve(path).name}").apply { parentFile?.mkdirs(); writeText(read(path).text) }
}
