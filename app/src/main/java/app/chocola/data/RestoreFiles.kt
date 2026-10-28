package app.chocola.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import org.json.JSONObject

/** Journal file swaps before changing anything; a DB marker decides recovery after process death. */
internal class RestoreFiles(private val root: File) {
    val staging = File(root, ".restore-state")
    val incoming = File(staging, "incoming")
    private val previous = File(staging, "previous")
    private val journal = File(root, ".restore-journal.json")
    fun recover(committedId: String?) {
        if (!journal.exists()) { deleteTree(staging); return }
        val state = JSONObject(journal.readText())
        if (committedId != state.getString("id")) {
            state.getJSONArray("roots").objects().forEach { entry ->
                val name = entry.getString("name"); require(name in roots)
                val saved = File(previous, name); val target = File(root, name)
                if (Files.exists(saved.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(target); check(saved.renameTo(target)) { "原数据回滚失败" }
                } else if (!entry.getBoolean("existed")) deleteTree(target)
            }
        }
        deleteTree(staging)
        check(journal.delete()) { "恢复事务清理失败" }
    }
    fun apply(id: String, commit: () -> Unit) {
        val state = JSONObject().put("id", id).put("roots", jsonArray(roots.map { name ->
            JSONObject().put("name", name).put("existed", Files.exists(File(root, name).toPath(), LinkOption.NOFOLLOW_LINKS))
        }))
        val temporary = File(root, ".restore-journal.tmp")
        FileOutputStream(temporary).use { out -> out.write(state.toString().toByteArray()); out.fd.sync() }
        check(temporary.renameTo(journal)) { "恢复事务无法保存" }
        previous.mkdirs()
        roots.forEach { name ->
            val target = File(root, name); val staged = File(incoming, "payload/$name")
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) check(target.renameTo(File(previous, name))) { "原文件无法保留" }
            if (Files.exists(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) check(staged.renameTo(target)) { "备份文件无法移入" }
        }
        commit()
    }
    companion object {
        val roots = listOf("workspaces", "sandboxes", "checkpoints", "skills", "chat-backgrounds", "notebooks", "annotation-drafts")
        fun deleteTree(file: File) {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult { dir.toFile().setWritable(true, true); return FileVisitResult.CONTINUE }
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(path); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE }
            })
        }
    }
}
