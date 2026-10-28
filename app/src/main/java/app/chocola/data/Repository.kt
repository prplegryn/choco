package app.chocola.data

import android.content.Context
import app.chocola.agent.AgentEngine
import app.chocola.agent.LinuxRuntime
import app.chocola.agent.ScheduleRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.Build
import android.app.ActivityManager
import org.json.JSONObject
import java.io.File

class Repository(val context: Context, val scope: CoroutineScope, val logs: Diagnostics) {
    private val database = SecureDatabase(context, logs)
    private val sessionLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val messageWindows = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val mutable = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutable
    private val draftLock = Mutex()
    private val draftsMutable = MutableStateFlow<Map<String, AttachmentDraft>>(emptyMap())
    val attachmentDrafts: StateFlow<Map<String, AttachmentDraft>> = draftsMutable
    val annotations = AnnotationStore(File(context.filesDir, "annotation-drafts"), scope) { name, fields -> logs.event("annotation.$name", fields) }
    private fun putAttachmentDraft(id: String, value: AttachmentDraft) {
        if (value.items.isEmpty()) database.remove("attachment-draft", id)
        else database.put("attachment-draft", id, value.json().put("session", id))
        draftsMutable.update { if (value.items.isEmpty()) it - id else it + (id to value) }
    }
    suspend fun addDraftAttachments(id: String, files: List<Attachment>) = withContext(Dispatchers.IO) { draftLock.withLock {
        putAttachmentDraft(id, (draftsMutable.value[id] ?: AttachmentDraft()).add(files))
    } }
    suspend fun updateDraftAttachment(id: String, draftId: String, image: Attachment) = withContext(Dispatchers.IO) { draftLock.withLock {
        val old = draftsMutable.value[id] ?: return@withLock
        putAttachmentDraft(id, old.copy(items = old.items.map { if (it.draftId == draftId) image else it }))
    } }
    suspend fun removeDraftAttachments(id: String, removed: List<Attachment>) = withContext(Dispatchers.IO) { draftLock.withLock {
        val old = draftsMutable.value[id] ?: return@withLock
        val ids = removed.map { it.draftId }.toSet()
        putAttachmentDraft(id, old.copy(items = old.items.filterNot { it.draftId in ids }))
        ids.filter(String::isNotBlank).forEach { annotations.discard(it) }
    } }
    val notebooks = NotebookStore(File(context.filesDir, "notebooks"), changed = {
        mutable.update { it.copy(notebooksVersion = it.notebooksVersion + 1) }
        logs.event("notebook.changed")
    }, log = { event, fields -> logs.event("notebook.$event", fields) })
    val runtime = LinuxRuntime(context, logs, scope, { value -> mutable.update { it.copy(runtime = value) } }, { processes -> mutable.update { it.copy(processes = processes) } })
    val skillStore = app.chocola.agent.SkillStore(this)
    val mcp = app.chocola.agent.McpManager(this)
    val backups = AppBackup(this, database)
    val codexAccounts = CodexAccounts(this, database)
    internal fun codexState(value: CodexState) { mutable.update { it.copy(codex = value) } }
    private val transferLock = Mutex()
    private val balanceLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val modelLock = Mutex()
    private val memoryLock = Mutex()
    private val balanceClient = app.chocola.api.BalanceClient(logs = logs)
    val engine = AgentEngine(this)
    val scheduler = ScheduleRunner(this)
    private val scheduleLock = Mutex()
    private val scheduleAnswers = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<ScheduleConsent?>>()
    fun answerSchedule(id: String, consent: ScheduleConsent?) { scheduleAnswers[id]?.complete(consent) }
    private suspend fun authorizeSchedule(value: ScheduledTask): ScheduledTask {
        val previous = state.value.schedules.firstOrNull { it.id == value.id }
        val changed = previous == null || previous.authorizationVersion == 0 || previous.prompt != value.prompt ||
            previous.providerId != value.providerId || previous.modelSnapshot != value.modelSnapshot || previous.access != value.access || previous.credentialIds != value.credentialIds || previous.allowedTools != value.allowedTools
        if (!value.enabled || !changed) return value
        mutable.update { it.copy(scheduleChecks = it.scheduleChecks + value.id) }
        try {
            logs.event("schedule.preflight.start", mapOf("id" to value.id))
            val requirements = app.chocola.agent.SchedulePreflight(this).inspect(value)
            val request = ScheduleAuthorization(task = value, requirements = requirements)
            val answer = CompletableDeferred<ScheduleConsent?>()
            scheduleAnswers[request.id] = answer
            mutable.update { it.copy(scheduleAuthorizations = it.scheduleAuthorizations + request) }
            val consent = try { withTimeoutOrNull(5 * 60_000L) { answer.await() } } finally {
                scheduleAnswers.remove(request.id)
                mutable.update { it.copy(scheduleAuthorizations = it.scheduleAuthorizations.filterNot { r -> r.id == request.id }) }
            } ?: error("任务未保存，尚未获得执行授权")
            require(consent.tools.containsAll(requirements.tools)) { "任务所需工具尚未全部授权" }
            require(consent.credentials.containsAll(requirements.credentials)) { "任务所需资料尚未全部授权" }
            val memories = consent.credentials.map { id -> state.value.memories.firstOrNull { it.id == id } ?: error("所选资料已删除") }
            val missing = memories.flatMap(ScheduleRequirements::missing)
            require(missing.isEmpty()) { missing.joinToString("\n") }
            require(!requirements.needsCredential || memories.any { requirements.credentialKind.isBlank() || it.kind == requirements.credentialKind }) { "请选择任务需要的凭据资料" }
            require(requirements.issues.isEmpty()) { requirements.issues.joinToString("\n") }
            val checked = value.copy(allowedTools = consent.tools, credentialIds = consent.credentials,
                access = if (consent.tools.isNotEmpty() && value.access in setOf(Access.CHAT, Access.PLAN)) Access.ASK else value.access, authorizationVersion = 1)
            for (memory in memories) app.chocola.agent.Tools(this).verifyCredential(checked, memory)
            val mcpTools = consent.tools.filter(McpNames::isTool).toSet()
            if (mcpTools.isNotEmpty()) {
                val candidates = state.value.mcpServers.filter { server -> server.enabled && org.json.JSONArray(server.tools).objects().any { McpNames.alias(server, it) in mcpTools } }
                val verified = mutableSetOf<String>()
                for (server in candidates) {
                    val ready = mcp.check(server)
                    verified += org.json.JSONArray(ready.tools).objects().filter { it.getString("name") !in ready.disabledTools }.map { McpNames.alias(ready, it) }
                }
                require(verified.containsAll(mcpTools)) { "MCP 工具已变更，请重新检查服务后再授权任务" }
            }
            logs.event("schedule.preflight.done", mapOf("id" to value.id, "tools" to consent.tools.sorted(), "credentialCount" to memories.size))
            return checked
        } finally { mutable.update { it.copy(scheduleChecks = it.scheduleChecks - value.id) } }
    }
    private val startedAt = android.os.SystemClock.elapsedRealtime()
    private val firstContent = MutableStateFlow(false)
    val firstFrameReady: Boolean get() = firstContent.value
    @Volatile private var frameStartedAt: Long = startedAt
    fun beginFirstFrame() { firstContent.value = false; frameStartedAt = android.os.SystemClock.elapsedRealtime() }
    suspend fun awaitFirstContent() { firstContent.first { it } }
    fun firstFrame() {
        if (firstContent.compareAndSet(false, true)) {
            logs.event("startup.content_ready", mapOf("elapsedMs" to android.os.SystemClock.elapsedRealtime() - frameStartedAt))
        }
    }
    init { scope.launch(Dispatchers.IO) { reload() } }
    private suspend fun reload() = coroutineScope {
            try {
                backups.recover()
                annotations.reload()
                draftsMutable.value = database.load("attachment-draft").associate { it.getString("session") to AttachmentDraft.from(it) }
                codexAccounts.reload()
                val savedProviders = database.load("provider").map(Provider::from)
                val savedVendors = database.load("vendor").map(ApiVendor::from)
                val migration = VendorAccounts.migrate(savedVendors, savedProviders)
                val providers = migration.models
                val vendors = migration.vendors
                if (providers != savedProviders || vendors != savedVendors) database.transaction {
                    vendors.forEach { database.put("vendor", it.id, it.json()) }
                    providers.forEach { database.put("provider", it.id, it.json()) }
                }
                vendors.forEach { registerProvider(it.request()) }
                val memories = database.load("memory").map(Memory::from)
                val skills = database.load("skill").map(Skill::from)
                val mcpServers = database.load("mcp").map(McpServer::from)
                mcpServers.forEach(::registerMcp)
                val preferences = database.load("preferences").firstOrNull()?.let(Preferences::from) ?: Preferences()
                val wallpaper = async { runCatching { app.chocola.ui.WallpaperImages.prepare(context, preferences.chatBackground, logs = logs) }
                    .onFailure { logs.error("startup.wallpaper.failed", it) } }
                val interruptedSchedules = mutableListOf<String>()
                val savedSchedules = database.load("schedule").map(ScheduledTask::from).map { task ->
                    if (task.activeRun.isNotBlank() || task.lastStatus == "running") {
                        val interrupted = task.activeSessionId.ifBlank { if (task.authorizationVersion == 0) task.sessionId else task.resultSessionId }
                        interruptedSchedules += interrupted
                        database.remove("task", interrupted)
                        task.copy(activeRun = "", activeSessionId = "", lastStatus = "interrupted", lastDetail = "应用进程中断，未自动重放").also { database.put("schedule", it.id, it.json()) }
                    } else task
                }
                val saved = database.load("app").firstOrNull()
                val sessions = database.load("session").map(Session::from).ifEmpty {
                    listOf(Session(providerId = providers.firstOrNull()?.id.orEmpty(), access = preferences.defaultAccess).also { persistSession(it) })
                }
                val schedules = savedSchedules.map { task ->
                    if (task.providerId.isNotBlank()) task else {
                        val source = sessions.firstOrNull { it.id == task.sessionId }
                        val model = providers.firstOrNull { it.id == source?.providerId }
                        if (source != null && model != null) task.withModel(source, model).also { database.put("schedule", it.id, it.json()) } else task
                    }
                }
                val current = sessions.firstOrNull { it.id == saved?.optString("current") }
                    ?: sessions.filter { it.scheduledTaskId.isBlank() }.maxByOrNull { it.updated } ?: sessions.maxBy { it.updated }
                providers.forEach { registerProvider(it) }; memories.forEach { registerMemory(it) }
                // Recover explicit interrupted states; never replay filesystem/network writes.
                var initialMessages = emptyList<Message>()
                (listOf(current.id) + interruptedSchedules + database.load("task").map { it.optString("sessionId") }).filter(String::isNotBlank).distinct().forEach { id ->
                    val page = database.page(id, limit = if (id == current.id) 32 else 80).map(Message::from).map {
                        if (it.status == "streaming" || it.status == "running") it.copy(status = "interrupted", error = "任务已中断，可继续发送消息").also(::persistMessage) else it
                    }
                    if (id == current.id) initialMessages = page
                }
                wallpaper.await()
                mutable.value = AppState(loaded = true, sessions = sessions, providers = providers, vendors = vendors, memories = memories,
                    preferences = preferences, currentId = current.id, schedules = schedules, skills = skills, mcpServers = mcpServers, transfer = state.value.transfer, codex = codexAccounts.snapshot,
                    messages = mapOf(current.id to initialMessages), runtime = runtime.state, historyMore = mapOf(current.id to (database.count(current.id) > initialMessages.size)))
                scheduler.initialize()
                logs.event("store.loaded", mapOf("sessions" to sessions.size, "providers" to providers.size, "memories" to memories.size,
                    "initialMessages" to initialMessages.size, "elapsedMs" to android.os.SystemClock.elapsedRealtime() - startedAt))
                scope.launch(Dispatchers.IO) { runCatching { codexAccounts.synchronizeModels() }.onFailure { logs.error("codex.models.restore.failed", it) } }
                scope.launch(Dispatchers.IO) { runCatching { app.chocola.ui.WallpaperImages.prepare(context, preferences.chatBackground, persist = true, logs = logs) } }
            } catch (e: Exception) {
                logs.error("store.failed", e)
                mutable.update { it.copy(loaded = true, notice = "本地数据未能打开：${e.message}") }
                firstFrame()
            }
    }
    fun alarmReady(value: Boolean) { mutable.update { it.copy(scheduleAlarmReady = value) } }
    suspend fun changeSchedule(id: String, change: (ScheduledTask) -> ScheduledTask): ScheduledTask? = withContext(Dispatchers.IO) {
        scheduleLock.withLock {
            val old = state.value.schedules.firstOrNull { it.id == id } ?: return@withLock null
            val next = change(old)
            database.put("schedule", id, next.json())
            mutable.update { it.copy(schedules = it.schedules.map { value -> if (value.id == id) next else value }) }
            next
        }
    }
    suspend fun saveSchedule(value: ScheduledTask): ScheduledTask = withContext(Dispatchers.IO) {
        value.validate()
        val requestedAt = System.currentTimeMillis()
        val model = state.value.providers.firstOrNull { it.id == value.providerId } ?: error("请选择任务使用的模型")
        val authorized = authorizeSchedule(value.copy(modelSnapshot = model.model))
        val saved = scheduleLock.withLock {
            val old = state.value.schedules.firstOrNull { it.id == value.id }
            val task = authorized.copy(activeRun = old?.activeRun.orEmpty(), activeSessionId = old?.activeSessionId.orEmpty(), lastResultSessionId = old?.lastResultSessionId.orEmpty(), runCount = old?.runCount ?: 0, lastAt = old?.lastAt ?: 0,
                lastStatus = old?.lastStatus.orEmpty(), lastDetail = old?.lastDetail.orEmpty(), modelSnapshot = model.model)
            val candidate = ScheduleTimes.next(task, maxOf(requestedAt, task.startAt - 1))
            val next = task.copy(nextAt = if (candidate > 0 && System.currentTimeMillis() - candidate <= 15 * 60_000) candidate
                else ScheduleTimes.next(task, maxOf(System.currentTimeMillis(), task.startAt - 1)))
            require(!next.enabled || next.nextAt > 0 || (next.maxRuns > 0 && next.runCount >= next.maxRuns)) { "该计划没有未来的执行时间，请调整开始或结束时间" }
            database.put("schedule", next.id, next.json())
            mutable.update { it.copy(schedules = it.schedules.filterNot { t -> t.id == next.id } + next) }
            next
        }
        scheduler.refresh()
        logs.event("schedule.saved", mapOf("id" to saved.id, "session" to saved.sessionId, "nextAt" to saved.nextAt, "frequency" to saved.frequency))
        saved
    }
    suspend fun deleteSchedule(id: String) = withContext(Dispatchers.IO) {
        scheduleLock.withLock {
            database.remove("schedule", id)
            mutable.update { it.copy(schedules = it.schedules.filterNot { t -> t.id == id }) }
        }
        scheduler.refresh()
        logs.event("schedule.deleted", mapOf("id" to id))
    }
    private fun registerProvider(p: Provider) { Redactor.register(p.apiKey); p.headers.values.forEach(Redactor::register) }
    private fun registerMemory(m: Memory) { m.fields.filter { it.secret }.forEach { Redactor.register(it.value) } }
    private fun persistSession(s: Session) = database.put("session", s.id, s.json(), created = s.updated)
    private fun persistMessage(m: Message) = database.put("message", m.id, m.json(), m.sessionId, m.created)
    private fun saveToolArtifact(message: Message) {
        if (message.role != "tool" || message.text.length <= 12000) return
        runCatching { File(workspace(message.sessionId), ".chocola/outputs/${message.id}.txt").apply { parentFile?.mkdirs(); writeText(Redactor.clean(message.text)) } }
            .onFailure { logs.error("tool.artifact.failed", it, mapOf("session" to message.sessionId, "message" to message.id)) }
    }
    fun notice(value: String?) { mutable.update { it.copy(notice = value) } }
    fun filesChanged() { mutable.update { it.copy(filesVersion = it.filesVersion + 1) } }
    fun retainVisibleHistory(id: String) { messageWindows.compute(id) { _, previous -> maxOf(previous ?: 80, 160) } }
    fun followRecentHistory(id: String) {
        messageWindows[id] = 80
        mutable.update { s ->
            val current = s.messages[id].orEmpty()
            if (current.size <= 80) s else s.copy(messages = cache(s, id, current.takeLast(80)), historyMore = s.historyMore + (id to true))
        }
    }
    fun phase(session: String, value: String = "") {
        mutable.update { it.copy(tasks = if (value.isBlank()) it.tasks - session else it.tasks + (session to value)) }
        logs.event("task.phase", mapOf("session" to session, "phase" to value))
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val summary = "sessions=${state.value.tasks.keys.joinToString { it.take(8) }};processes=${state.value.activeProcessCount}"
            context.getSystemService(ActivityManager::class.java).setProcessStateSummary(summary.toByteArray().take(128).toByteArray())
        }
    }
    fun approval(session: String, value: Approval?) { mutable.update { it.copy(approvals = if (value == null) it.approvals - session else it.approvals + (session to value)) } }
    suspend fun checkpoint(value: TaskCheckpoint) = withContext(Dispatchers.IO) { database.put("task", value.sessionId, value.json()) }
    suspend fun clearCheckpoint(session: String) = withContext(Dispatchers.IO) { database.remove("task", session) }
    suspend fun checkpoints(): List<TaskCheckpoint> = withContext(Dispatchers.IO) { database.load("task").map(TaskCheckpoint::from) }
    suspend fun pendingInputs(id: String): List<Message> = withContext(Dispatchers.IO) { database.load("steering", id).map(Message::from) }
    suspend fun withdrawInput(id: String) = withContext(Dispatchers.IO) {
        val saved = database.get("steering", id)?.let(Message::from) ?: return@withContext
        putMessage(saved.copy(status = "withdrawn"))
    }
    fun workspace(sessionId: String): File {
        SessionIds.requireValid(sessionId)
        return File(context.filesDir, "workspaces/$sessionId").apply { mkdirs() }
    }
    suspend fun select(id: String) = withContext(Dispatchers.IO) {
        val snapshot = state.value
        if (snapshot.sessions.none { it.id == id }) return@withContext
        if (snapshot.currentId == id && snapshot.messages.containsKey(id)) return@withContext
        val cached = snapshot.messages[id]
        val messages = cached ?: buildList {
            val limit = messageWindows[id] ?: 80
            while (size < limit) {
                val page = database.page(id, limit = minOf(200, limit - size), offset = size).map(Message::from)
                if (page.isEmpty()) break
                addAll(0, page)
            }
        }
        val more = database.count(id) > messages.size
        messageWindows.putIfAbsent(id, 80)
        database.put("app", "state", JSONObject().put("current", id))
        mutable.update { s -> s.copy(currentId = id, messages = cache(s, id, overlay(messages, s.messages[id].orEmpty())), historyMore = s.historyMore + (id to more)) }
        logs.event("session.select", mapOf("id" to id, "cached" to (cached != null), "messages" to messages.size))
    }
    suspend fun createSession(): Session = withContext(Dispatchers.IO) {
        val s = Session(providerId = state.value.current?.providerId ?: state.value.providers.firstOrNull()?.id.orEmpty(), access = state.value.preferences.defaultAccess,
            reasoningSelections = state.value.preferences.reasoningDefaults)
        persistSession(s)
        mutable.update { it.copy(sessions = it.sessions + s, currentId = s.id, messages = it.messages + (s.id to emptyList())) }
        database.put("app", "state", JSONObject().put("current", s.id))
        s
    }
    suspend fun chooseReasoning(id: String, provider: Provider, level: String) {
        updateSession(id) { ThinkingControls.choose(it, provider, level) }
        preferences(state.value.preferences.copy(reasoningDefaults = state.value.preferences.reasoningDefaults + (ThinkingControls.modelKey(provider) to level)))
    }
    /** The plan owns this session and its sandbox. Its source conversation is never a dependency. */
    suspend fun prepareScheduleSession(task: ScheduledTask): Session = withContext(Dispatchers.IO) {
        val provider = state.value.providers.firstOrNull { it.id == task.providerId } ?: error("任务模型已删除")
        val old = state.value.sessions.firstOrNull { it.id == task.resultSessionId }
        val base = (old ?: Session(id = task.resultSessionId)).copy(title = task.title, titleMode = "manual", titleLocked = true,
            providerId = task.providerId, access = task.access, scheduledTaskId = task.id, taskTools = task.allowedTools, taskCredentials = task.credentialIds)
        val session = ThinkingControls.choose(base, provider, task.reasoningLevel.takeIf { key -> ThinkingControls.options(provider).any { it.key == key } }.orEmpty())
        if (old != null) updateSession(old.id) { session } else {
            persistSession(session)
            mutable.update { it.copy(sessions = it.sessions + session) }
        }
        session
    }
    suspend fun updateSession(id: String, change: (Session) -> Session) = withContext(Dispatchers.IO) { sessionLocks.getOrPut(id) { Mutex() }.withLock {
        val before = state.value.sessions.firstOrNull { it.id == id } ?: return@withLock
        val next = change(before)
        if (before.access != next.access) {
            engine.revokeGrants(id)
            if (id in state.value.tasks && (next.access in setOf(Access.CHAT, Access.PLAN) || before.access == Access.FULL)) engine.stop(id, "会话权限已变更")
            if (before.access == Access.FULL && next.access != Access.FULL) runtime.stopSessionProcesses(id)
        }
        persistSession(next)
        mutable.update { it.copy(sessions = it.sessions.map { old -> if (old.id == id) next else old }) }
    } }
    suspend fun saveDraft(id: String, draft: String) = updateSession(id) { it.copy(draft = draft) }
    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        engine.stopAndJoin(id)
        removeDraftAttachments(id, attachmentDrafts.value[id]?.items.orEmpty())
        mcp.closeSession(id)
        runtime.dispose(id)
        clearCheckpoint(id)
        database.remove("session", id); database.removeChildren(id)
        state.value.schedules.filter { it.lastResultSessionId == id }.forEach { task -> changeSchedule(task.id) { it.copy(lastResultSessionId = "") } }
        workspace(id).deleteRecursively()
        mutable.update { it.copy(sessions = it.sessions.filterNot { s -> s.id == id }, messages = it.messages - id) }
        val next = state.value.sessions.filter { it.scheduledTaskId.isBlank() }.maxByOrNull { it.updated }
        if (next == null) createSession() else if (state.value.currentId == id) select(next.id)
    }
    private fun overlay(saved: List<Message>, live: List<Message>): List<Message> {
        val replacements = live.associateBy { it.id }
        val ids = saved.mapTo(HashSet()) { it.id }
        return saved.map { replacements[it.id] ?: it } + live.filter { it.id !in ids }
    }
    private fun cache(s: AppState, id: String, values: List<Message>): Map<String, List<Message>> {
        val required = (listOf(id, s.currentId) + s.tasks.keys).toSet()
        val keys = required + s.messages.keys.filterNot { it in required }.takeLast((3 - required.size).coerceAtLeast(0))
        return (s.messages.filterKeys { it in keys } + (id to values))
    }
    suspend fun loadOlder(id: String) = withContext(Dispatchers.IO) { sessionLocks.getOrPut(id) { Mutex() }.withLock {
        val existing = state.value.messages[id].orEmpty()
        val older = database.page(id, offset = existing.size).map(Message::from)
        val total = database.count(id)
        messageWindows[id] = maxOf(160, existing.size + older.size)
        mutable.update { s ->
            val current = s.messages[id].orEmpty()
            val combined = (older + current).distinctBy { it.id }
            s.copy(messages = cache(s, id, combined), historyMore = s.historyMore + (id to (combined.size < total)))
        }
        logs.event("history.page", mapOf("session" to id, "loaded" to older.size))
    } }
    suspend fun allMessages(id: String): List<Message> = withContext(Dispatchers.IO) {
        overlay(database.load("message", id).map(Message::from), state.value.messages[id].orEmpty()).filter { it.status != "replaced" }
    }
    suspend fun userTurns(id: String): Int = withContext(Dispatchers.IO) {
        var page = database.page(id).map(Message::from)
        var turns = 0
        while (page.isNotEmpty()) {
            turns += page.count { it.role == "user" }
            page = database.before(id, page.first().id).map(Message::from)
        }
        turns
    }
    suspend fun titleMessages(id: String): List<Message> = withContext(Dispatchers.IO) {
        val saved = (database.first(id).map(Message::from) + database.page(id).map(Message::from)).distinctBy { it.id }
        val ids = saved.mapTo(HashSet()) { it.id }
        overlay(saved, state.value.messages[id].orEmpty().filter { it.id in ids }).filter { it.status != "replaced" }
    }
    suspend fun toolDetails(id: String, messageId: String): Pair<Message, ToolCall?>? = withContext(Dispatchers.IO) {
        val live = state.value.messages[id].orEmpty()
        val message = live.firstOrNull { it.id == messageId } ?: database.get("message", messageId)?.let(Message::from) ?: return@withContext null
        if (message.sessionId != id) return@withContext null
        var call = live.flatMap { it.calls }.firstOrNull { it.id == message.toolCallId }
        var anchor = message.id
        while (call == null) {
            val page = database.before(id, anchor).map(Message::from)
            if (page.isEmpty()) break
            call = page.asReversed().flatMap { it.calls }.firstOrNull { it.id == message.toolCallId }
            if (call != null || page.any { it.role == "user" }) break
            anchor = page.first().id
        }
        message to call
    }
    suspend fun contextTail(id: String, through: String, anchorUser: String): List<Message> = withContext(Dispatchers.IO) {
        val saved = database.after(id, through).map(Message::from)
        val ids = saved.mapTo(HashSet()) { it.id }
        val latest = state.value.messages[id].orEmpty().filter { it.id in ids || it.status == "streaming" }
        val tail = overlay(saved, latest).filter { it.status != "replaced" }
        val anchor = if (anchorUser.isNotBlank() && tail.none { it.role == "user" }) database.get("message", anchorUser)?.let(Message::from) else null
        if (anchor != null) listOf(AttachmentInputs.summarized(anchor)) + tail else tail
    }
    suspend fun putMessage(message: Message, persist: Boolean = true) = withContext(Dispatchers.IO) {
        if (persist) {
            if (message.role == "user") database.transaction {
                persistMessage(message)
                if (message.status == "queued") database.put("steering", message.id, message.json(), message.sessionId, message.created)
                else database.remove("steering", message.id)
            } else persistMessage(message)
            saveToolArtifact(message)
        }
        mutable.update { s ->
            val list = s.messages[message.sessionId].orEmpty()
            val updated = if (list.any { it.id == message.id }) list.map { if (it.id == message.id) message else it } else list + message
            val max = messageWindows[message.sessionId] ?: 80
            val visible = if (message.sessionId == s.currentId && max > 80) updated else updated.takeLast(max)
            s.copy(messages = cache(s, message.sessionId, visible), historyMore = if (visible.size < updated.size) s.historyMore + (message.sessionId to true) else s.historyMore)
        }
    }
    suspend fun retryMessage(sessionId: String, messageId: String) = withContext(Dispatchers.IO) {
        check(sessionId !in state.value.tasks) { "请等待这个会话的任务结束" }
        val recent = database.page(sessionId).map(Message::from)
        val ids = recent.mapTo(HashSet()) { it.id }
        val live = state.value.messages[sessionId].orEmpty().filter { it.id in ids }
        val message = RetryPolicy.target(overlay(recent, live), messageId)
        putMessage(message.copy(status = "replaced"))
        logs.event("session.retry", mapOf("session" to sessionId, "message" to messageId))
    }
    suspend fun renameSession(id: String, name: String) = updateSession(id) { it.copy(title = name.trim().take(80), titleMode = "manual") }
    suspend fun fork(messageId: String, replacement: String? = null, sessionId: String = state.value.currentId): Session = withContext(Dispatchers.IO) {
        val source = state.value.sessions.first { it.id == sessionId }
        val all = allMessages(source.id)
        val index = all.indexOfFirst { it.id == messageId }
        require(index >= 0)
        val branch = source.copy(id = newId(), title = source.title, updated = System.currentTimeMillis(), summary = "", summaryThrough = "", draft = replacement ?: "", plan = "", summaryUserId = "", summaryAt = 0, branchOf = source.id, titleMode = "auto", titledTurns = 0, userTurns = all.take(index).count { it.role == "user" }, titleLocked = false, scheduledTaskId = "", taskTools = emptySet(), taskCredentials = emptySet())
        persistSession(branch)
        // Copy files too, so attachment references and tool workspace outputs remain valid.
        val sourceRoot = workspace(source.id)
        sourceRoot.copyRecursively(workspace(branch.id), overwrite = true)
        val copied = all.take(index).map { m ->
            m.copy(id = newId(), sessionId = branch.id, attachments = m.attachments.map { a ->
                a.copy(path = a.path.replace(sourceRoot.absolutePath, workspace(branch.id).absolutePath))
            })
        }
        copied.forEach { persistMessage(it); saveToolArtifact(it) }
        mutable.update { it.copy(sessions = it.sessions + branch, currentId = branch.id, messages = cache(it, branch.id, copied.takeLast(80)), historyMore = it.historyMore + (branch.id to (copied.size > 80))) }
        database.put("app", "state", JSONObject().put("current", branch.id))
        logs.event("session.fork", mapOf("source" to source.id, "branch" to branch.id, "messages" to copied.size))
        branch
    }
    suspend fun saveSkills(values: List<Skill>) = withContext(Dispatchers.IO) {
        database.transaction { values.forEach { SkillFormat.validateName(it.name); database.put("skill", it.name, it.json()) } }
        mutable.update { it.copy(skills = it.skills.filterNot { old -> values.any { s -> s.name == old.name } } + values) }
    }
    suspend fun saveSkill(skill: Skill) = saveSkills(listOf(skill))
    suspend fun deleteSkill(name: String) = withContext(Dispatchers.IO) {
        SkillFormat.validateName(name)
        database.remove("skill", name); skillStore.directory(name).deleteRecursively()
        mutable.update { it.copy(skills = it.skills.filterNot { s -> s.name == name }) }
    }
    private fun registerMcp(server: McpServer) {
        Redactor.register(server.token); server.headers.values.forEach(Redactor::register); server.environment.values.forEach(Redactor::register)
        McpNames.register(server)
    }
    suspend fun saveMcp(server: McpServer) = withContext(Dispatchers.IO) {
        server.validate(); registerMcp(server); mcp.closeServer(server.id)
        database.put("mcp", server.id, server.json())
        mutable.update { it.copy(mcpServers = it.mcpServers.filterNot { s -> s.id == server.id } + server) }
        state.value.sessions.forEach { engine.revokeGrants(it.id) }
    }
    suspend fun deleteMcp(id: String) = withContext(Dispatchers.IO) {
        mcp.closeServer(id); database.remove("mcp", id)
        mutable.update { it.copy(mcpServers = it.mcpServers.filterNot { s -> s.id == id }) }
    }
    suspend fun refreshBalance(provider: Provider, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (CodexWire.isCodex(provider)) return@withContext
        val key = ModelAccounts.key(provider)
        balanceLocks.getOrPut(key) { Mutex() }.withLock {
            val previous = state.value.balances[key]
            if (!force && previous != null && System.currentTimeMillis() - previous.time < 120_000) return@withLock
            try {
                val balance = balanceClient.query(provider)
                mutable.update { it.copy(balances = it.balances + (key to balance)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                logs.error("balance.query.failed", e, mapOf("host" to ModelAccounts.host(provider)))
                mutable.update { it.copy(balances = it.balances + (key to ModelBalance("查询失败", Redactor.clean(e.message.orEmpty()), System.currentTimeMillis(), true))) }
            }
        }
    }
    suspend fun saveProviders(providers: List<Provider>) = withContext(Dispatchers.IO) {
        val values = modelLock.withLock {
            val values = providers.map { model ->
                require(model.model.isNotBlank()) { "请填写模型 ID" }
                val vendor = state.value.vendors.firstOrNull { it.id == model.vendorId } ?: error("请先保存服务商 API")
                vendor.attach(model.copy(model = model.model.trim())).also { registerProvider(it) }
            }
            val next = state.value.providers.filterNot { old -> values.any { it.id == old.id } } + values
            require(values.none { model -> next.any { it.id != model.id && it.vendorId == model.vendorId && it.model == model.model } }) { "该服务商已添加此模型" }
            database.transaction { values.forEach { database.put("provider", it.id, it.json()) } }
            mutable.update { it.copy(providers = next) }
            values
        }
        // Adding or tuning a model must not replace an already selected conversation model.
        state.value.current?.takeIf { current -> state.value.providers.none { it.id == current.providerId } }?.let { current ->
            values.firstOrNull()?.let { first -> updateSession(current.id) { if (state.value.providers.none { p -> p.id == it.providerId }) it.copy(providerId = first.id) else it } }
        }
    }

    suspend fun saveVendor(vendor: ApiVendor) = withContext(Dispatchers.IO) { modelLock.withLock {
        require(vendor.id != CodexWire.VENDOR) { "Codex 账号请在 Codex 设置中管理" }
        vendor.validate(); registerProvider(vendor.request())
        val previous = state.value.vendors.firstOrNull { it.id == vendor.id }
        val models = state.value.providers.filter { it.vendorId == vendor.id }.map { model ->
            vendor.attach(if (previous != null && previous.protocol != vendor.protocol && model.protocol == previous.protocol) model.copy(protocol = vendor.protocol) else model)
        }
        database.transaction {
            database.put("vendor", vendor.id, vendor.json())
            models.forEach { database.put("provider", it.id, it.json()) }
        }
        mutable.update { it.copy(vendors = it.vendors.filterNot { v -> v.id == vendor.id } + vendor,
            providers = it.providers.map { p -> models.firstOrNull { m -> m.id == p.id } ?: p }) }
        logs.event("vendor.saved", mapOf("id" to vendor.id, "host" to vendor.host, "models" to models.size))
    } }

    suspend fun deleteVendor(id: String) = withContext(Dispatchers.IO) { modelLock.withLock {
        val ids = state.value.providers.filter { it.vendorId == id }.map { it.id }.toSet()
        require(state.value.sessions.none { it.providerId in ids && it.id in state.value.tasks }) { "请先结束该服务商正在运行的任务" }
        require(state.value.schedules.none { it.providerId in ids && (it.enabled || it.activeRun.isNotBlank()) }) { "请先为相关定时任务更换模型或停用任务" }
        database.transaction { ids.forEach { database.remove("provider", it) }; database.remove("vendor", id) }
        mutable.update { it.copy(vendors = it.vendors.filterNot { v -> v.id == id }, providers = it.providers.filterNot { p -> p.id in ids }) }
        logs.event("vendor.deleted", mapOf("id" to id, "models" to ids.size))
    } }
    private suspend fun <T> transfer(label: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        transferLock.withLock { scheduler.whilePaused {
            require(!state.value.hasBackgroundWork && state.value.scheduleChecks.isEmpty()) { "请先结束正在运行的任务" }
            mutable.update { it.copy(transfer = label) }
            try { engine.stopMetadata(); codexAccounts.awaitIdle(); annotations.flush(); mcp.closeAll(); block() } finally { mutable.update { it.copy(transfer = "") } }
        } }
    }
    suspend fun exportBackup(password: CharArray, output: java.io.OutputStream) = transfer("正在导出完整备份") {
        val file = backups.export(password)
        try { file.inputStream().use { source ->
            val bytes = ByteArray(65536)
            while (true) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); val count = source.read(bytes); if (count < 0) break; output.write(bytes, 0, count) }
            output.flush()
        } } finally { file.delete() }
    }
    suspend fun restoreBackup(input: java.io.InputStream, password: CharArray) = transfer("正在恢复完整备份") {
        backups.restore(input, password)
        engine.resetGrants()
        messageWindows.clear(); sessionLocks.clear()
        withContext(kotlinx.coroutines.NonCancellable) {
            app.chocola.ui.WallpaperImages.clear()
            reload()
        }
    }
    suspend fun saveProvider(provider: Provider) {
        if (CodexWire.isCodex(provider)) require(state.value.providers.any { it.id == provider.id && it.model == provider.model && it.protocol == provider.protocol } ||
            (provider.model in CodexImageModels.names && provider.id == CodexModel(provider.model).provider().id && codexAccounts.snapshot.accounts.isNotEmpty())) { "Codex 模型由账号自动获取" }
        saveProviders(listOf(provider))
        logs.event("provider.saved", mapOf("id" to provider.id, "protocol" to provider.protocol.name, "model" to provider.model))
    }
    internal suspend fun synchronizeCodexModels(models: List<Provider>) = withContext(Dispatchers.IO) { modelLock.withLock {
        val old = state.value.providers.filter(CodexWire::isCodex)
        val vendor = CodexWire.vendor()
        val next = models.map { model ->
            val previous = old.firstOrNull { it.id == model.id }
            vendor.attach(previous?.copy(reasoningLevels = model.reasoningLevels, contextWindow = model.contextWindow,
                reasoningEffort = previous.reasoningEffort.takeIf { it.isBlank() || it in model.reasoningLevels || model.reasoningLevels.isEmpty() } ?: model.reasoningEffort) ?: model)
        }
        val referenced = state.value.sessions.map { it.providerId }.toSet() + state.value.schedules.map { it.providerId }
        val kept = next + old.filter { it.id in referenced && next.none { n -> n.id == it.id } }
        database.transaction {
            if (kept.isNotEmpty()) database.put("vendor", vendor.id, vendor.json()) else database.remove("vendor", vendor.id)
            old.filter { p -> kept.none { it.id == p.id } }.forEach { database.remove("provider", it.id) }
            kept.forEach { database.put("provider", it.id, it.json()) }
        }
        mutable.update { state -> state.copy(vendors = state.vendors.filterNot { it.id == vendor.id } + if (kept.isEmpty()) emptyList() else listOf(vendor),
            providers = state.providers.filterNot(CodexWire::isCodex) + kept) }
        state.value.current?.takeIf { current -> state.value.providers.none { it.id == current.providerId } }?.let { current ->
            next.firstOrNull()?.let { model -> updateSession(current.id) { it.copy(providerId = model.id) } }
        }
    } }
    suspend fun deleteProvider(id: String) = withContext(Dispatchers.IO) { modelLock.withLock {
        require(state.value.sessions.none { it.providerId == id && it.id in state.value.tasks }) { "请先结束此模型正在运行的任务" }
        require(state.value.schedules.none { it.providerId == id && (it.enabled || it.activeRun.isNotBlank()) }) { "请先为相关定时任务更换模型或停用任务" }
        database.remove("provider", id)
        mutable.update { it.copy(providers = it.providers.filterNot { p -> p.id == id }) }
    } }
    suspend fun rememberMemory(name: String, content: String, scope: String) = withContext(Dispatchers.IO) { memoryLock.withLock {
        val next = MemoryWrites.remember(database.load("memory").map(Memory::from), name, content, scope)
        database.transaction {
            database.get("memory", next.id)?.takeIf { it.toString() != next.json().toString() }?.let { previous ->
                database.put("memory-revision", newId(), previous, parent = next.id, created = System.currentTimeMillis())
            }
            database.put("memory", next.id, next.json())
        }
        mutable.update { it.copy(memories = it.memories.filterNot { m -> m.id == next.id } + next) }
        logs.event("memory.remembered", mapOf("id" to next.id))
    } }
    suspend fun saveMemory(memory: Memory) = withContext(Dispatchers.IO) { memoryLock.withLock {
        require(memory.name.isNotBlank()) { "请填写名称" }
        require(memory.fields.all { it.name.isNotBlank() }) { "字段名称不能为空" }
        require(memory.fields.map { it.name }.distinct().size == memory.fields.size) { "字段名称不能重复" }
        registerMemory(memory)
        database.put("memory", memory.id, memory.json())
        mutable.update { it.copy(memories = it.memories.filterNot { m -> m.id == memory.id } + memory) }
        logs.event("memory.saved", mapOf("id" to memory.id, "kind" to memory.kind, "fields" to memory.fields.size))
    } }
    suspend fun deleteMemory(id: String) = withContext(Dispatchers.IO) { memoryLock.withLock {
        database.remove("memory", id); database.removeChildren(id, "memory-revision")
        mutable.update { it.copy(memories = it.memories.filterNot { m -> m.id == id }) }
    } }
    suspend fun preferences(value: Preferences) = withContext(Dispatchers.IO) {
        if (value.chatBackground != state.value.preferences.chatBackground)
            runCatching { app.chocola.ui.WallpaperImages.prepare(context, value.chatBackground, persist = true, logs = logs) }.onFailure { logs.error("wallpaper.prepare.failed", it) }
        database.put("preferences", "main", value.json())
        mutable.update { it.copy(preferences = value) }
        scheduler.refresh()
        logs.event("ui.appearance.saved", mapOf("fontScale" to value.fontScale, "pill" to value.pill.json().toString()))
    }
    suspend fun exportMarkdown(id: String): File = withContext(Dispatchers.IO) {
        val title = state.value.sessions.firstOrNull { it.id == id }?.title ?: "会话"
        val file = File(context.cacheDir, "exports/$id.md").apply { parentFile?.mkdirs() }
        file.writeText("# $title\n\n" + allMessages(id).filterNot { it.hidden }.joinToString("\n\n") { m ->
            when (m.role) { "user" -> "## 你\n\n${m.text}"; "tool" -> "### ${m.toolName}\n\n```text\n${Redactor.clean(m.text)}\n```"; else -> m.text }
        })
        file
    }
}
