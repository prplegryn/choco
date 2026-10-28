package app.chocola.data

import org.json.JSONObject
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Export must stay inside the same bounds that the restore path accepts. */
internal class BackupBudget {
    private var bytes = 0L
    private var files = 3 // manifest, records, file metadata
    private var records = 0
    fun consume(count: Long) { bytes += count; require(bytes <= 16_000_000_000L) { "备份解压后超过 16 GB，无法恢复" } }
    fun file() { require(++files <= 200_000) { "备份文件超过可恢复数量" } }
    fun record(record: JSONObject, line: String) {
        require(++records <= 1_000_000 && line.length <= 16_000_000) { "备份记录超过可恢复上限" }
        require(record.getString("kind").length in 1..64 && record.getString("id").length in 1..300) { "备份记录标识无效" }
    }
}

internal object FullBackupFiles {
    fun write(root: File, zip: ZipOutputStream, metadata: File, budget: BackupBudget, check: () -> Unit) {
        metadata.bufferedWriter().use { meta ->
            RestoreFiles.roots.forEach { name ->
                val directory = File(root, name)
                if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
                require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "应用数据目录异常：$name" }
                Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                    private fun record(path: Path, attrs: BasicFileAttributes, type: String) {
                        check()
                        val relative = "payload/" + root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                        ArchivePaths.validate(relative, linuxNames = true)
                        budget.file()
                        val json = JSONObject().put("path", relative).put("type", type).put("modified", attrs.lastModifiedTime().toMillis())
                        if (type == "link") json.put("target", Files.readSymbolicLink(path).toString())
                        else json.put("mode", mode(path))
                        val line = json.toString(); require(line.length <= 16_000) { "文件清单条目过长" }; meta.write(line); meta.newLine()
                        if (type == "file") {
                            zip.putNextEntry(ZipEntry(relative).apply { time = attrs.lastModifiedTime().toMillis() })
                            Files.newInputStream(path).use { input -> val buffer = ByteArray(32768)
                                while (true) { check(); val count = input.read(buffer); if (count < 0) break; budget.consume(count.toLong()); zip.write(buffer, 0, count) }
                            }
                            zip.closeEntry()
                        }
                    }
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult { record(dir, attrs, "directory"); return FileVisitResult.CONTINUE }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        require(attrs.isRegularFile || attrs.isSymbolicLink) { "备份遇到特殊文件：${file.fileName}" }
                        record(file, attrs, if (attrs.isSymbolicLink) "link" else "file"); return FileVisitResult.CONTINUE
                    }
                })
            }
        }
    }
    fun restore(staging: File, check: () -> Unit) {
        val file = File(staging, "files.jsonl")
        require(file.isFile) { "备份缺少文件清单" }
        val directories = mutableListOf<JSONObject>(); val links = mutableListOf<JSONObject>(); val seen = mutableSetOf<String>()
        file.bufferedReader().useLines { lines -> lines.forEach { line ->
            check(); require(line.length <= 16_000) { "文件清单异常" }
            val entry = JSONObject(line); val path = entry.getString("path")
            require(path.startsWith("payload/") && path.substringAfter("payload/").substringBefore('/') in RestoreFiles.roots && seen.add(path)) { "备份文件清单无效" }
            val target = ArchivePaths.resolve(staging, path, linuxNames = true)
            when (entry.getString("type")) {
                "directory" -> { require(!target.isFile); target.mkdirs(); directories += entry }
                "link" -> { require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)); links += entry }
                "file" -> { require(target.isFile && !Files.isSymbolicLink(target.toPath())) { "备份缺少文件：$path" }; attributes(target, entry) }
                else -> error("备份包含不支持的文件类型")
            }
        } }
        links.forEach { entry ->
            val target = ArchivePaths.resolve(staging, entry.getString("path"), linuxNames = true)
            val destination = entry.getString("target"); require(destination.length in 1..8192 && !destination.contains('\u0000')) { "符号链接无效" }
            target.parentFile?.mkdirs(); require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
            Files.createSymbolicLink(target.toPath(), Paths.get(destination))
        }
        directories.asReversed().forEach { attributes(ArchivePaths.resolve(staging, it.getString("path"), linuxNames = true), it) }
    }
    private val permissions = listOf(PosixFilePermission.OWNER_READ to 256, PosixFilePermission.OWNER_WRITE to 128, PosixFilePermission.OWNER_EXECUTE to 64,
        PosixFilePermission.GROUP_READ to 32, PosixFilePermission.GROUP_WRITE to 16, PosixFilePermission.GROUP_EXECUTE to 8,
        PosixFilePermission.OTHERS_READ to 4, PosixFilePermission.OTHERS_WRITE to 2, PosixFilePermission.OTHERS_EXECUTE to 1)
    private fun mode(path: Path): Int = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).sumOf { permission -> permissions.first { it.first == permission }.second }
    private fun attributes(file: File, entry: JSONObject) {
        val mode = entry.getInt("mode"); require(mode in 0..511)
        Files.setPosixFilePermissions(file.toPath(), permissions.filter { mode and it.second != 0 }.map { it.first }.toSet())
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(entry.optLong("modified")))
    }

}
