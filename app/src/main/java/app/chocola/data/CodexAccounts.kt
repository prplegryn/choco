package app.chocola.data

import app.chocola.agent.TaskService
import app.chocola.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private data class CodexRequestAccount(val id: String, val token: String)
private data class CodexAffinity(val account: String, val detachedReasoning: Boolean)

/** Credentials never enter AppState, Provider, a localhost server, or public diagnostics. */
class CodexAccounts internal constructor(private val repository: Repository, private val database: SecureDatabase, private val client: CodexClient = CodexClient(logs = repository.logs)) : PrivateModelAccess {
    private val lock = Mutex()
    private val refreshLock = Mutex()
    private val tokenLocks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var accounts = emptyMap<String, CodexCredential>()
    private val errors = ConcurrentHashMap<String, String>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val cooldown = ConcurrentHashMap<String, Long>()
    private val lastUsed = ConcurrentHashMap<String, Long>()
    private val affinity = ConcurrentHashMap<String, CodexAffinity>()
    @Volatile private var login: CodexLoginInfo? = null
    @Volatile private var allRefreshing = false
    @Volatile private var globalError = ""
    private var loginJob: Job? = null
    private var pending: CodexDeviceCode? = null
    val snapshot get() = CodexState(accounts.values.map { it.info().copy(refreshing = it.id in refreshing, error = errors[it.id].orEmpty()) }.sortedBy { it.email }, login, allRefreshing, globalError)
    private fun publish() { repository.codexState(snapshot) }
    internal suspend fun reload() {
        loginJob?.cancelAndJoin()
        lock.withLock {
            accounts = database.load("codex-account").map(CodexCredential::from).map { it.copy(models = CodexImageModels.include(it.models)) }.associateBy { it.id }
            accounts.values.forEach(::register)
            errors.clear(); refreshing.clear(); cooldown.clear(); lastUsed.clear(); affinity.clear()
            pending = database.load("codex-login").firstOrNull()?.let(CodexDeviceCode::from)
            login = pending?.let { CodexLoginInfo(CodexClient.LOGIN_URL, it.code, it.expiresAt) }
            publish()
        }
        pending?.let { value ->
            loginJob = repository.scope.launch(Dispatchers.IO) { repository.state.first { it.loaded }; authorize(value) }
        }
    }
    private fun register(account: CodexCredential) { Redactor.register(account.accessToken); Redactor.register(account.refreshToken); Redactor.register(account.idToken) }
    private suspend fun update(id: String, change: (CodexCredential) -> CodexCredential): CodexCredential = lock.withLock {
        val old = accounts[id] ?: error("账号已移除")
        change(old).also { next -> register(next); database.put("codex-account", id, next.json()); accounts = accounts + (id to next); publish() }
    }
    private suspend fun add(value: CodexCredential) = lock.withLock {
        val old = accounts[value.id]
        val next = value.copy(enabled = old?.enabled ?: true, models = old?.models.orEmpty(), quota = old?.quota ?: CodexQuota())
        register(next); database.put("codex-account", next.id, next.json()); accounts = accounts + (next.id to next)
        errors.remove(next.id); cooldown.remove(next.id); publish()
    }
    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        require(repository.state.value.transfer.isBlank()) { "数据迁移中，请稍后" }
        update(id) { it.copy(enabled = enabled) }
        repository.logs.event("codex.account.enabled", mapOf("id" to id, "enabled" to enabled))
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        require(repository.state.value.transfer.isBlank()) { "数据迁移中，请稍后" }
        lock.withLock { database.remove("codex-account", id); accounts = accounts - id; errors.remove(id); cooldown.remove(id); publish() }
        synchronizeModels()
        repository.logs.event("codex.account.removed", mapOf("id" to id))
    }
    fun startLogin() {
        if (repository.state.value.transfer.isNotBlank()) return
        if (loginJob?.isActive == true) return
        login = CodexLoginInfo(starting = true); publish()
        loginJob = repository.scope.launch(Dispatchers.IO) {
            try {
                repository.phase("codex-login", "正在等待账号授权")
                TaskService.start(repository.context)
                val code = client.begin()
                database.put("codex-login", "pending", code.json()); pending = code
                login = CodexLoginInfo(CodexClient.LOGIN_URL, code.code, code.expiresAt); publish()
                authorize(code, serviceStarted = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { login = CodexLoginInfo(error = Redactor.clean(e.message ?: "登录失败")); publish(); repository.logs.error("codex.login.failed", e) }
            finally { withContext(NonCancellable) { repository.phase("codex-login"); TaskService.stopIfIdle(repository.context) } }
        }
    }
    private suspend fun authorize(code: CodexDeviceCode, serviceStarted: Boolean = false) {
        try {
            Redactor.register(code.device); Redactor.register(code.code)
            repository.phase("codex-login", "正在等待账号授权")
            if (!serviceStarted) TaskService.start(repository.context)
            while (System.currentTimeMillis() < code.expiresAt) {
                delay(code.interval)
                val result = try { client.poll(code) } catch (e: CodexHttpFailure) {
                    if (e.status == 429 || e.status >= 500) { delay(10_000); continue } else throw e
                } catch (e: java.io.IOException) { repository.logs.event("codex.login.network_wait"); delay(5000); continue }
                if (result != null) {
                    val credential = CodexTokens.credential(result)
                    withContext(NonCancellable + Dispatchers.IO) {
                        add(credential); database.remove("codex-login", "pending"); pending = null; login = null; publish()
                    }
                    repository.logs.event("codex.login.completed", mapOf("id" to credential.id))
                    refresh()
                    return
                }
            }
            error("登录地址已过期，请重新添加账号")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            database.remove("codex-login", "pending"); pending = null
            login = CodexLoginInfo(error = Redactor.clean(e.message ?: "登录失败")); publish()
            repository.logs.error("codex.login.failed", e)
        } finally { withContext(NonCancellable) { repository.phase("codex-login"); TaskService.stopIfIdle(repository.context) } }
    }
    suspend fun cancelLogin() = withContext(Dispatchers.IO) {
        loginJob?.cancelAndJoin(); loginJob = null
        database.remove("codex-login", "pending"); pending = null; login = null; publish()
    }
    private suspend fun token(id: String, rejectedToken: String = ""): CodexCredential = tokenLocks.getOrPut(id) { Mutex() }.withLock {
        val old = accounts[id] ?: error("账号已移除")
        if (old.expiresAt > System.currentTimeMillis() + 90_000 && (rejectedToken.isBlank() || old.accessToken != rejectedToken)) return@withLock old
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                val next = CodexTokens.credential(client.refresh(old.refreshToken), old)
                update(id) { current -> next.copy(enabled = current.enabled, models = current.models, quota = current.quota) }
            }
        } catch (e: CodexHttpFailure) {
            if (e.status in setOf(400, 401, 403)) update(id) { it.copy(needsLogin = true) }
            errors[id] = e.message.orEmpty(); publish(); throw e
        }
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (repository.state.value.transfer.isNotBlank()) return@withContext
        if (!refreshLock.tryLock()) return@withContext
        try {
            if (repository.state.value.transfer.isNotBlank()) return@withContext
            allRefreshing = true; globalError = ""; publish()
            val semaphore = Semaphore(3)
            supervisorScope { accounts.keys.toList().map { id -> launch { semaphore.withPermit { refreshAccount(id) } } }.joinAll() }
            synchronizeModels()
        } finally { allRefreshing = false; publish(); refreshLock.unlock() }
    }
    internal suspend fun awaitIdle() { refreshLock.withLock { lock.withLock { } } }
    private suspend fun refreshAccount(id: String) {
        refreshing += id; errors.remove(id); publish()
        val failures = mutableListOf<String>()
        try {
            var current = token(id)
            suspend fun <T> get(block: suspend (CodexCredential) -> T): T {
                return try { block(current) } catch (e: CodexHttpFailure) {
                    if (e.status != 401) throw e
                    current = token(id, current.accessToken); block(current)
                }
            }
            try {
                val usage = get(client::usage)
                val quota = CodexQuota.usage(usage)
                update(id) { it.copy(quota = quota, plan = usage.optString("plan_type").ifBlank { it.plan }, needsLogin = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += e.message ?: "额度刷新失败" }
            try {
                val models = get(client::models)
                require(models.isNotEmpty()) { "账号暂未返回可用模型" }
                update(id) { it.copy(models = CodexImageModels.include(models), needsLogin = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += e.message ?: "模型刷新失败" }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failures += e.message ?: "刷新失败" }
        finally {
            if (failures.isNotEmpty() && accounts.containsKey(id)) errors[id] = Redactor.clean(failures.distinct().joinToString("；"))
            refreshing.remove(id); publish()
            repository.logs.event("codex.account.refreshed", mapOf("id" to id, "success" to failures.isEmpty(), "modelCount" to accounts[id]?.models?.size))
        }
    }
    internal suspend fun synchronizeModels() {
        val models = accounts.values.flatMap { it.models }.groupBy { it.slug }.map { (_, values) ->
            values.first().copy(efforts = values.flatMap { it.efforts }.distinct())
        }
        repository.synchronizeCodexModels(models.map(CodexModel::provider))
    }
    override suspend fun models(): List<String> = accounts.values.flatMap { it.models }.map { it.slug }.distinct().sorted()
    private suspend fun selectAccount(provider: Provider, session: String): Pair<CodexCredential, CodexAffinity> {
        val now = System.currentTimeMillis()
        val candidates = CodexPool.eligible(accounts.values, provider, now) { cooldown.getOrDefault(it, 0) > now }
        require(candidates.isNotEmpty()) {
            when {
                accounts.isEmpty() -> "请先在 Codex 设置中添加账号"
                accounts.values.none { it.enabled } -> "Codex 账号均已停用，请启用一个账号"
                accounts.values.filter { it.enabled }.all { it.needsLogin } -> "Codex 登录已失效，请重新添加账号"
                else -> "当前没有可用的 Codex 账号，请检查模型、思考强度或额度；可在 Codex 设置中刷新"
            }
        }
        val old = affinity[session] ?: database.get("codex-affinity", session)?.let { CodexAffinity(it.optString("account"), it.optBoolean("detachedReasoning")) }
        val ordered = candidates.sortedWith(compareBy<CodexCredential> { it.id != old?.account }.thenBy { lastUsed.getOrDefault(it.id, 0) })
        var ready: CodexCredential? = null
        var rejected: Exception? = null
        for (candidate in ordered) {
            try {
                val renewed = token(candidate.id)
                if (accounts[renewed.id]?.enabled == true) { ready = renewed; break }
            } catch (e: CodexHttpFailure) { if (e.status !in setOf(400, 401, 403)) throw e; rejected = e }
        }
        val account = ready ?: throw (rejected ?: IllegalStateException("没有启用且有效的 Codex 账号"))
        val binding = CodexAffinity(account.id, old?.detachedReasoning == true || (old != null && old.account != account.id))
        affinity[session] = binding
        database.put("codex-affinity", session, JSONObject().put("account", binding.account).put("detachedReasoning", binding.detachedReasoning))
        lastUsed[account.id] = now
        return account to binding
    }
    override suspend fun request(provider: Provider, session: String, body: JSONObject): Request = withContext(Dispatchers.IO) {
        val (account, binding) = selectAccount(provider, session)
        val data = CodexWire.normalize(body, binding.detachedReasoning)
        val cacheKey = ModelAccounts.fingerprint("choco:$session").take(32)
        data.put("prompt_cache_key", cacheKey)
        repository.logs.event("codex.request.prepared", mapOf("session" to session, "account" to account.id, "model" to provider.model, "detachedReasoning" to binding.detachedReasoning))
        Request.Builder().url(CodexWire.BASE + "/responses").tag(CodexRequestAccount::class.java, CodexRequestAccount(account.id, account.accessToken))
            .header("Authorization", "Bearer ${account.accessToken}").header("Chatgpt-Account-Id", account.accountId)
            .header("User-Agent", CodexWire.USER_AGENT).header("Originator", "codex-tui").header("Accept", "text/event-stream")
            .header("Session-Id", cacheKey).post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }
    override suspend fun imageRequest(provider: Provider, session: String, request: Request): Request = withContext(Dispatchers.IO) {
        require(request.url.scheme == "https" && request.url.host == "chatgpt.com" && request.url.encodedPath in
            setOf("/backend-api/codex/images/generations", "/backend-api/codex/images/edits", "/backend-api/codex/responses")) { "绘图请求地址无效" }
        // Separate affinity avoids moving a text conversation's encrypted reasoning between accounts.
        val (account, _) = selectAccount(provider, "image:$session")
        repository.logs.event("codex.image.prepared", mapOf("session" to session, "account" to account.id, "model" to provider.model,
            "path" to request.url.encodedPath))
        request.newBuilder().tag(CodexRequestAccount::class.java, CodexRequestAccount(account.id, account.accessToken))
            .header("Authorization", "Bearer ${account.accessToken}").header("Chatgpt-Account-Id", account.accountId)
            .header("User-Agent", CodexWire.USER_AGENT).header("Originator", "codex-tui")
            .header("Session-Id", ModelAccounts.fingerprint("choco:image:$session").take(32)).build()
    }
    override suspend fun response(request: Request, response: Response) {
        val tag = request.tag(CodexRequestAccount::class.java) ?: return
        if (!accounts.containsKey(tag.id)) return
        if (response.code == 401) {
            // Refresh only after a definitive rejection, never replay an accepted execution.
            try { token(tag.id, tag.token) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors[tag.id] = "登录续期失败，请重新添加账号"; publish() }
        }
        if (response.code == 429) {
            val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400) ?: 60
            cooldown[tag.id] = System.currentTimeMillis() + wait * 1000
        }
        val now = System.currentTimeMillis()
        fun window(prefix: String): CodexWindow? {
            val used = response.header("x-codex-$prefix-used-percent")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            val duration = response.header("x-codex-$prefix-window-minutes")?.toLongOrNull()?.times(60) ?: return null
            val resets = response.header("x-codex-$prefix-reset-after-seconds")?.toLongOrNull()?.let { now + it * 1000 } ?: 0L
            return CodexWindow(used.coerceIn(0.0, 100.0), duration, resets)
        }
        val windows = listOfNotNull(window("primary"), window("secondary"))
        if (windows.isNotEmpty()) update(tag.id) { account -> account.copy(quota = account.quota.copy(windows = (windows + account.quota.windows).distinctBy { it.seconds }, checkedAt = now)) }
    }
}
