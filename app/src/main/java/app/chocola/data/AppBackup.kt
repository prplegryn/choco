package app.chocola.data

import app.chocola.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppBackup(private val repository: Repository, private val database: SecureDatabase) {
    private val root get() = repository.context.filesDir
    private fun transaction() = RestoreFiles(root)
    fun recover() {
        transaction().recover(database.get("restore", "marker")?.optString("id"))
        database.remove("restore", "marker")
    }
    suspend fun export(password: CharArray): File = withContext(Dispatchers.IO) {
        val output = File(repository.context.cacheDir, "backups/${newId()}.chocola").apply { parentFile?.mkdirs() }
        output.parentFile?.listFiles()?.filter { it != output }?.forEach { it.delete() }
        val metadata = File(repository.context.cacheDir, "backup-meta-${newId()}.jsonl")
        val caller = currentCoroutineContext()
        val budget = BackupBudget()
        try {
            output.outputStream().buffered().use { destination ->
                BackupCipher.encrypt(destination, password) { caller.ensureActive() }.use { encrypted ->
                    ZipOutputStream(encrypted).use { zip ->
                        zip.setLevel(3)
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        val manifest = JSONObject().put("format", "chocola.full-backup").put("version", 1).put("appVersion", BuildConfig.VERSION_NAME)
                            .put("created", System.currentTimeMillis()).put("filesDir", root.absolutePath).toString().toByteArray(Charsets.UTF_8)
                        budget.consume(manifest.size.toLong()); zip.write(manifest)
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("records.jsonl"))
                        val records = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
                        database.exportRecords { value ->
                            caller.ensureActive()
                            val line = value.toString(); budget.record(value, line)
                            prepareRecord(value.getString("kind"), JSONObject(value.getJSONObject("value").toString()), root.absolutePath)
                            budget.consume(line.toByteArray(Charsets.UTF_8).size.toLong() + 1)
                            records.write(line); records.newLine()
                        }
                        records.flush(); zip.closeEntry()
                        FullBackupFiles.write(root, zip, metadata, budget) { caller.ensureActive() }
                        budget.consume(metadata.length())
                        zip.putNextEntry(ZipEntry("files.jsonl")); metadata.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
            }
            repository.logs.event("backup.exported", mapOf("bytes" to output.length()))
            output
        } catch (e: Exception) { output.delete(); throw e }
        finally { metadata.delete(); password.fill('\u0000') }
    }
    suspend fun restore(input: InputStream, password: CharArray) = withContext(Dispatchers.IO) {
        recover()
        val transaction = transaction()
        val zipFile = File(transaction.staging, "verified.zip").apply { parentFile?.mkdirs() }
        val caller = currentCoroutineContext()
        val restoreId = newId()
        var committed = false
        try {
            // Verify every chunk and the final tag before parsing or swapping application data.
            try {
                input.use { source -> zipFile.outputStream().buffered().use { out -> BackupCipher.decrypt(source, out, password) { caller.ensureActive() } } }
            } catch (e: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("密码不正确，或备份已损坏", e) }
            catch (e: EOFException) { throw IllegalArgumentException("备份不完整，原有数据未改动", e) }
            transaction.incoming.mkdirs()
            zipFile.inputStream().use { ArchivePaths.unpack(it, transaction.incoming, 16_000_000_000L, 200_000, linuxNames = true) }
            val manifest = JSONObject(File(transaction.incoming, "manifest.json").readText())
            require(manifest.getString("format") == "chocola.full-backup" && manifest.getInt("version") == 1) { "备份版本不支持" }
            val oldRoot = manifest.getString("filesDir").trimEnd('/')
            require(oldRoot.startsWith('/') && oldRoot.length < 1024) { "备份目录无效" }
            val sourceRecords = File(transaction.incoming, "records.jsonl")
            val readyRecords = File(transaction.staging, "ready-records.jsonl")
            val seen = mutableSetOf<String>(); var sessions = 0
            readyRecords.bufferedWriter().use { out ->
                sourceRecords.bufferedReader().useLines { rows -> rows.forEach { line ->
                    caller.ensureActive(); require(line.length <= 16_000_000 && seen.size < 1_000_000) { "备份记录过大" }
                    val record = JSONObject(line); val kind = record.getString("kind")
                    require(kind.length in 1..64 && record.getString("id").length in 1..300 && seen.add("$kind:${record.getString("id")}")) { "备份记录重复或无效" }
                    if (kind !in setOf("task", "restore")) {
                        prepareRecord(kind, record.getJSONObject("value"), oldRoot)
                        if (kind == "session") sessions++
                        out.write(record.toString()); out.newLine()
                    }
                } }
            }
            require(sessions > 0) { "备份未包含会话数据" }
            FullBackupFiles.restore(transaction.incoming) { caller.ensureActive() }
            caller.ensureActive()
            withContext(NonCancellable) {
                try {
                    transaction.apply(restoreId) { readyRecords.bufferedReader().useLines { rows -> database.replaceRecords(rows.map(::JSONObject), restoreId) } }
                    committed = true
                } finally { recover() }
            }
            repository.logs.event("backup.restored", mapOf("records" to seen.size, "sessions" to sessions))
        } finally {
            password.fill('\u0000')
            if (!committed) withContext(NonCancellable) { recover() }
        }
    }
    private fun prepareRecord(kind: String, value: JSONObject, oldRoot: String) {
        fun path(raw: String): String = if (raw.startsWith("$oldRoot/")) root.absolutePath + raw.removePrefix(oldRoot) else raw
        when (kind) {
            "provider" -> { Provider.from(value); value.put("name", "") }
            "vendor" -> ApiVendor.from(value).validate()
            "memory" -> Memory.from(value)
            "session" -> { val s = Session.from(value); SessionIds.requireValid(s.id) }
            "message", "steering" -> {
                Message.from(value)
                value.optJSONArray("attachments")?.objects()?.forEach { it.put("path", path(it.getString("path"))) }
                if (value.optString("status") in setOf("running", "streaming")) value.put("status", "interrupted").put("error", "备份时的任务未完成，可继续发送消息")
            }
            "preferences" -> {
                value.optJSONObject("chatBackground")?.let { it.put("image", path(it.optString("image"))) }
                value.put("setupCompleted", repository.state.value.preferences.setupCompleted)
                Preferences.from(value)
            }
            "skill" -> Skill.from(value).also { SkillFormat.validateName(it.name) }
            "mcp" -> McpServer.from(value).validate()
            "codex-account" -> CodexCredential.from(value)
            "attachment-draft" -> {
                SessionIds.requireValid(value.getString("session"))
                value.optJSONArray("items")?.objects()?.forEach { image ->
                    image.put("path", path(image.getString("path")))
                    if (image.optString("originalPath").isNotBlank()) image.put("originalPath", path(image.getString("originalPath")))
                }
                AttachmentDraft.from(value)
            }
            "schedule" -> {
                ScheduledTask.from(value)
                if (value.optString("activeRun").isNotBlank() || value.optString("lastStatus") == "running")
                    value.put("activeRun", "").put("activeSessionId", "").put("lastStatus", "interrupted").put("lastDetail", "备份中的运行状态已结束，后续按计划执行")
            }
        }
    }
}
