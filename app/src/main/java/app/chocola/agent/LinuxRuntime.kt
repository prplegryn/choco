package app.chocola.agent

import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.Os
import app.chocola.data.Diagnostics
import app.chocola.data.Redactor
import app.chocola.data.SessionIds
import app.chocola.data.RuntimeState
import app.chocola.data.SandboxProcess
import app.chocola.data.newId
import app.chocola.data.strings
import app.chocola.data.jsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class LinuxRuntime(private val context: Context, private val logs: Diagnostics, private val scope: CoroutineScope,
    private val onState: (RuntimeState) -> Unit, private val onProcesses: (List<SandboxProcess>) -> Unit) {
    private val installMutex = Mutex()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val root = File(context.filesDir, "runtime-base-v2")
    private val sandboxes = File(context.filesDir, "sandboxes")
    private val foreground = ConcurrentHashMap<String, Process>()
    private data class Background(val record: SandboxProcess, val process: Process, var job: Job? = null)
    private val background = ConcurrentHashMap<String, Background>()
    @Volatile var state = RuntimeState(if (File(root, ".ready").exists()) "已就绪" else "未准备")
        private set
    private fun update(value: RuntimeState) { state = value; onState(value) }
    private suspend fun prepare() = withContext(Dispatchers.IO) { installMutex.withLock {
        if (File(root, ".ready").exists()) { update(RuntimeState("已就绪", 1f)); return@withLock }
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "当前安装包需要 arm64 设备" }
        update(RuntimeState("正在准备", .01f))
        val staging = File(context.filesDir, "runtime-base-installing")
        try {
            staging.deleteRecursively(); staging.mkdirs()
            var links = JSONObject()
            var executables = JSONArray()
            var files = 0
            ZipInputStream(context.assets.open("runtime.zip").buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val file = File(staging, entry.name).canonicalFile
                    require(file.path.startsWith(staging.canonicalPath + File.separator)) { "运行环境包含非法路径" }
                    when (entry.name) {
                        "_chocola_links.json" -> links = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        "_chocola_exec.json" -> executables = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                        else -> if (entry.isDirectory) file.mkdirs() else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { zip.copyTo(it) }
                            files++
                            if (files % 100 == 0) update(RuntimeState("正在准备", (files / 8000f).coerceAtMost(.88f)))
                        }
                    }
                    zip.closeEntry()
                }
            }
            executables.strings().forEach { name -> File(staging, name).setExecutable(true, true) }
            links.keys().asSequence().sortedBy { it.count { c -> c == '/' } }.forEach { name ->
                val file = File(staging, name)
                require(file.parentFile!!.canonicalPath.startsWith(staging.canonicalPath))
                file.parentFile?.mkdirs()
                Os.symlink(links.getString(name), file.absolutePath)
            }
            File(staging, "tmp").mkdirs(); File(staging, "workspace").mkdirs(); File(staging, "shared").mkdirs()
            File(staging, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            File(staging, ".ready").writeText("1")
            root.deleteRecursively()
            check(staging.renameTo(root)) { "无法保存运行环境" }
            update(RuntimeState("已就绪", 1f))
            logs.event("runtime.ready", mapOf("files" to files, "native" to context.applicationInfo.nativeLibraryDir))
        } catch (e: Exception) {
            staging.deleteRecursively()
            update(RuntimeState("准备失败", error = e.message.orEmpty()))
            logs.error("runtime.install.failed", e)
            throw e
        }
    } }
    private fun directory(session: String): File {
        SessionIds.requireValid(session)
        return File(sandboxes, SessionIds.runtimeKey(session))
    }
    private suspend fun sessionRoot(session: String): File {
        prepare()
        val dir = directory(session)
        val target = File(dir, "root")
        if (File(target, ".ready").exists()) return target
        val staging = File(dir, "root-installing")
        staging.deleteRecursively(); staging.mkdirs()
        try {
            SandboxFiles.copy(root, staging)
            target.deleteRecursively()
            check(staging.renameTo(target)) { "无法创建会话环境" }
            logs.event("sandbox.created", mapOf("session" to session))
            return target
        } catch (e: Exception) { staging.deleteRecursively(); throw e }
    }
    private fun builder(command: String, workspace: File, sessionRoot: File, cwd: String,
        timeout: Int?, fullAccess: Boolean, environment: Map<String, String>): ProcessBuilder {
        require(cwd == "/workspace" || cwd.startsWith("/workspace/") || fullAccess) { "工作目录必须位于 /workspace" }
        require(command.length <= 100_000) { "命令过长" }
        val native = context.applicationInfo.nativeLibraryDir
        val temporary = File(directory(workspace.name), "tmp").apply { mkdirs() }
        // proot uses ':' to separate bind source/destination. Legacy report IDs
        // contain colons, so bind through a disposable, safely named alias.
        val bindWorkspace = if (workspace.name.contains(':')) {
            File(context.filesDir, "workspace-aliases/${SessionIds.runtimeKey(workspace.name)}").also { alias ->
                alias.parentFile!!.mkdirs()
                if (!java.nio.file.Files.exists(alias.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) Os.symlink(workspace.absolutePath, alias.absolutePath)
                require(alias.canonicalFile == workspace.canonicalFile) { "工作区路径异常" }
            }
        } else workspace
        val args = mutableListOf("$native/libproot.so", "--kill-on-exit", "--link2symlink", "-0", "-r", sessionRoot.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "${bindWorkspace.absolutePath}:/workspace", "-b", "${temporary.absolutePath}:/tmp", "-w", cwd)
        if (fullAccess && (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()))
            args += listOf("-b", "${Environment.getExternalStorageDirectory().absolutePath}:/shared")
        args += listOf("/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8", "TERM=xterm-256color", "PAGER=cat", "GIT_TERMINAL_PROMPT=0", "PYTHONUNBUFFERED=1")
        environment.forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "环境变量名称不合法" }
            args += "$key=$value"
        }
        if (timeout != null) args += listOf("/usr/bin/timeout", "--signal=TERM", "--kill-after=3s", "${timeout.coerceIn(1, 600)}s")
        args += listOf("/bin/bash", "--noprofile", "--norc", "-c", command)
        return ProcessBuilder(args).redirectErrorStream(true).directory(workspace).apply {
            environment().apply {
                clear(); put("PATH", "/system/bin"); put("LD_LIBRARY_PATH", native)
                put("PROOT_LOADER", "$native/libproot-loader.so"); put("PROOT_TMP_DIR", temporary.absolutePath); put("PROOT_NO_SECCOMP", "1")
            }
        }
    }
    suspend fun execute(command: String, workspace: File, cwd: String = "/workspace", timeout: Int = 120,
        fullAccess: Boolean = false, environment: Map<String, String> = emptyMap(), onOutput: suspend (String) -> Unit = {}): CommandResult {
        val span = logs.begin("process", session = workspace.name, fields = mapOf("commandPreview" to Redactor.clean(command.lineSequence().firstOrNull().orEmpty()).take(256),
            "commandChars" to command.length, "cwd" to cwd, "timeoutSeconds" to timeout, "fullAccess" to fullAccess))
        var outcome = "failed"
        span.stage("dispatch_io")
        try { return withContext(Dispatchers.IO) {
            span.stage("workspace_lock")
            locks.getOrPut(workspace.name) { Mutex() }.withLock {
            span.stage("prepare_runtime")
            val sessionRoot = sessionRoot(workspace.name)
            span.stage("spawn")
            val proc = builder(command, workspace, sessionRoot, cwd, timeout, fullAccess, environment).start()
            span.event("process.spawned")
            foreground[workspace.name] = proc
            proc.outputStream.close()
            try {
                val result = ProcessRunner.collect(proc, timeout, span) { onOutput(Redactor.clean(it)) }
                val clean = result.copy(output = Redactor.clean(result.output))
                outcome = if (clean.timedOut || clean.exitCode == 124) "timeout" else if (clean.exitCode == 0) "done" else "nonzero_exit"
                span.event("process.result", mapOf("exit" to clean.exitCode, "timedOut" to clean.timedOut, "outputChars" to clean.output.length))
                clean
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } finally {
                ProcessRunner.terminate(proc)
                foreground.remove(workspace.name, proc)
            }
        } } } catch (e: kotlinx.coroutines.CancellationException) { outcome = "cancelled"; throw e }
        finally { span.finish(outcome) }
    }
    /** MCP owns these pipes and lifetime; stderr must never be mixed into JSON-RPC stdout. */
    suspend fun openMcp(command: String, arguments: List<String>, workspace: File, environment: Map<String, String>): Process = withContext(Dispatchers.IO) {
        locks.getOrPut(workspace.name) { Mutex() }.withLock {
            val root = sessionRoot(workspace.name)
            val packages = when (command) { "node", "nodejs", "npm", "npx" -> "nodejs npm"; "uv", "uvx" -> "uv"; else -> "" }
            if (packages.isNotBlank()) {
                val setup = builder("command -v ${Tools.shellQuote(command)} >/dev/null 2>&1 || apk add --no-cache $packages",
                    workspace, root, "/workspace", 240, false, emptyMap()).start()
                setup.outputStream.close()
                try {
                    val result = ProcessRunner.collect(setup, 240) {}
                    require(result.exitCode == 0) { "MCP 运行依赖未能准备：${Redactor.clean(result.output).takeLast(500)}" }
                } finally { ProcessRunner.terminate(setup) }
            }
            builder("exec " + (listOf(command) + arguments).joinToString(" ", transform = Tools::shellQuote), workspace, root,
                "/workspace", null, false, mapOf("npm_config_yes" to "true", "UV_NO_PROGRESS" to "1") + environment).redirectErrorStream(false).start()
        }
    }
    fun stop(session: String) { foreground[session]?.let { runCatching { it.destroy() } } }
    @Synchronized private fun publishProcesses() = onProcesses(background.values.map { it.record })
    suspend fun startBackground(command: String, workspace: File, name: String, cwd: String, fullAccess: Boolean, environment: Map<String, String>): String = withContext(Dispatchers.IO) {
        locks.getOrPut(workspace.name) { Mutex() }.withLock {
            require(background.size < 12 && background.values.count { it.record.sessionId == workspace.name } < 4) { "后台进程已达上限，请先停止不再使用的进程" }
            val sessionRoot = sessionRoot(workspace.name)
            TaskService.start(context)
            val id = newId()
            val record = SandboxProcess(id, workspace.name, Redactor.clean(name).take(80).ifBlank { "后台任务" })
            val outputs = File(directory(workspace.name), "processes").apply { mkdirs() }
            val meta = File(outputs, "$id.json")
            saveRecord(meta, record)
            val process = try { builder(command, workspace, sessionRoot, cwd, null, fullAccess, environment).start() }
                catch (e: Exception) { saveRecord(meta, record.copy(status = "error")); throw e }
            process.outputStream.close()
            val handle = Background(record, process)
            background[id] = handle; publishProcesses()
            logs.event("background.started", mapOf("session" to workspace.name, "process" to id, "name" to record.name))
            val span = logs.begin("process", id = id, session = workspace.name, fields = mapOf("background" to true))
            handle.job = scope.launch(Dispatchers.IO) {
                var finalRecord = record
                try {
                    var last = 0L
                    val result = ProcessRunner.collect(process, null, span) { output ->
                        val now = System.currentTimeMillis()
                        if (now - last > 500) { File(outputs, "$id.log").writeText(Redactor.clean(output)); last = now }
                    }
                    File(outputs, "$id.log").writeText(Redactor.clean(result.output))
                    finalRecord = record.copy(status = "exited", exitCode = result.exitCode)
                } catch (e: CancellationException) { finalRecord = record.copy(status = "stopped") }
                catch (e: Exception) { logs.error("background.failed", e, mapOf("session" to workspace.name, "process" to id)); finalRecord = record.copy(status = "error") }
                finally {
                    withContext(NonCancellable) {
                        runCatching { saveRecord(meta, finalRecord) }.onFailure { logs.error("background.record.failed", it) }
                        background.remove(id); publishProcesses()
                        logs.event("background.ended", mapOf("session" to workspace.name, "process" to id, "status" to finalRecord.status, "exit" to finalRecord.exitCode))
                        span.finish(finalRecord.status, mapOf("exit" to finalRecord.exitCode))
                        TaskService.stopIfIdle(context)
                    }
                }
            }
            record.json().put("process_id", id).toString()
        }
    }
    private fun saveRecord(file: File, record: SandboxProcess) {
        val staging = File(file.parentFile, file.name + ".tmp")
        staging.writeText(record.json().toString())
        check(staging.renameTo(file)) { "无法保存后台进程状态" }
    }
    suspend fun processStatus(session: String, id: String): String = withContext(Dispatchers.IO) {
        val dir = File(directory(session), "processes")
        val records = if (id.isBlank()) dir.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.take(20)
            else { require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))); listOf(File(dir, "$id.json")) }
        jsonArray(records.filter { it.exists() }.map { file ->
            val record = SandboxProcess.from(JSONObject(file.readText()))
            val alive = background[record.id]?.record?.sessionId == session
            record.copy(status = if (record.status == "running" && !alive) "interrupted" else record.status).json()
                .put("process_id", record.id)
                .put("output", File(dir, "${record.id}.log").takeIf { it.exists() }?.readText()?.takeLast(24_000).orEmpty())
        }).toString()
    }
    suspend fun stopBackground(session: String, id: String): Boolean {
        val handle = background[id]?.takeIf { it.record.sessionId == session } ?: return false
        runCatching { handle.process.destroy() }
        handle.job?.cancel(); handle.job?.join()
        return true
    }
    suspend fun stopSessionProcesses(session: String) {
        background.values.filter { it.record.sessionId == session }.map { it.record.id }.forEach { stopBackground(session, it) }
    }
    suspend fun dispose(session: String) = withContext(Dispatchers.IO) {
        background.values.filter { it.record.sessionId == session }.map { it.record.id }.forEach { stopBackground(session, it) }
        locks.getOrPut(session) { Mutex() }.withLock { directory(session).deleteRecursively() }
        locks.remove(session)
    }
}
