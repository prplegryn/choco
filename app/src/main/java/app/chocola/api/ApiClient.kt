package app.chocola.api

import app.chocola.data.*
import app.chocola.data.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiClient(private val logs: Diagnostics? = null, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(25, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val mediaDirectory: ((String) -> File)? = null, private val temporaryDirectory: File = File(System.getProperty("java.io.tmpdir"), "choco-streams"),
    private val privateModels: PrivateModelAccess? = null) {
    private fun request(p: Provider, url: String): Request.Builder = Request.Builder().url(url).apply {
        Redactor.register(p.apiKey); p.headers.values.forEach(Redactor::register)
        header("Accept", "text/event-stream, application/json")
        if (p.apiKey.isNotBlank()) {
            if (p.protocol == Protocol.ANTHROPIC) header("x-api-key", p.apiKey) else header("Authorization", "Bearer ${p.apiKey}")
        }
        if (p.protocol == Protocol.ANTHROPIC) header("anthropic-version", "2023-06-01")
        p.headers.forEach { (name, value) -> header(name, value) }
    }
    private suspend fun Call.await(trace: StreamTrace? = null): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { trace?.callback(); if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                trace?.callback()
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> client.dispatcher.executorService.execute { value.close() } } else response.close()
            }
        })
    }
    suspend fun models(p: Provider): List<String> = withContext(Dispatchers.IO) {
        if (CodexWire.isCodex(p)) return@withContext requireNotNull(privateModels) { "请从 Codex 设置刷新模型" }.models()
        val call = client.traced(logs, "models").newCall(request(p, ProtocolCodec.endpoint(p, true)).get().build())
        coroutineScope {
        val watcher = launch(Dispatchers.IO) { try { awaitCancellation() } finally { call.cancel() } }
        try { call.await().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "模型列表请求失败 ${response.code}：${errorText(text)}" }
            val j = JSONObject(text)
            (j.optJSONArray("data") ?: j.optJSONArray("models") ?: JSONArray()).objects().map { it.optString("id", it.optString("name")) }.filter { it.isNotBlank() }.sorted()
        } } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally { watcher.cancel(); call.cancel() }
        }
    }
    suspend fun test(p: Provider): String {
        val result = stream(ModelCatalog.auxiliary(p, title = true), "Reply with OK.", listOf(Message(sessionId = "test", role = "user", text = "Say OK"))) {}
        check(result.text.isNotBlank()) { "模型未返回文本" }
        return result.text.take(120)
    }
    suspend fun stream(p: Provider, system: String, messages: List<Message>, tools: JSONArray = JSONArray(), onPhase: suspend (ReplyPhase) -> Unit = {}, onDelta: suspend (String) -> Unit): ApiResult {
        val operation = newId()
        val session = messages.lastOrNull()?.sessionId.orEmpty()
        val preparing = System.nanoTime()
        val body = withContext(Dispatchers.IO) {
            app.chocola.agent.ContextPlanner.requireFits(p, system, messages, tools.toString())
            ProtocolCodec.request(p, system, messages, tools)
        }
        suspend fun prepare(): Request = withContext(Dispatchers.IO) { if (CodexWire.isCodex(p)) requireNotNull(privateModels) { "Codex 账号接入未初始化" }.request(p, session, body)
            else request(p, ProtocolCodec.endpoint(p)).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }
        var request = prepare()
        logs?.event("api.prepared", mapOf("operation" to operation, "session" to session, "messages" to messages.size,
            "attachments" to messages.sumOf { it.attachments.size }, "tools" to tools.length(), "ms" to (System.nanoTime() - preparing) / 1_000_000.0))
        var emitted = false
        var modelStarted = false
        for (attempt in 0..2) {
            try {
                val response = once(p, request, operation, session, attempt, { modelStarted = true }, onPhase) { delta -> if (delta.isNotEmpty()) emitted = true; onDelta(delta) }
                logs?.event("api.delivered", mapOf("operation" to operation, "session" to session, "request" to response.request,
                    "afterReadMs" to (System.nanoTime() - response.finishedNs) / 1_000_000.0))
                return response.result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (CodexWire.isCodex(p) && !emitted && !modelStarted && attempt < 2 && e is ApiFailure && e.code in setOf(401, 429)) {
                    logs?.event("codex.preflight_retry", mapOf("operation" to operation, "attempt" to attempt + 1, "status" to e.code))
                    request = prepare()
                    continue
                }
                if (emitted || modelStarted || attempt == 2 || e !is IOException || (e is ApiFailure && !e.retryable)) throw e
                logs?.event("api.retry", mapOf("operation" to operation, "session" to session, "attempt" to attempt + 1, "reason" to e.javaClass.simpleName))
                delay((attempt + 1) * 1500L)
            }
        }
        error("请求未完成")
    }
    private data class AttemptResult(val result: ApiResult, val request: String, val finishedNs: Long)
    private suspend fun once(p: Provider, request: Request, operation: String, session: String, attempt: Int, onStarted: () -> Unit, onPhase: suspend (ReplyPhase) -> Unit, onDelta: suspend (String) -> Unit): AttemptResult = withContext(Dispatchers.IO) { coroutineScope {
        val id = newId()
        val trace = if (logs != null) StreamTrace(logs, id, session, operation, attempt) else null
        val activity = StreamActivity(p.protocol)
        trace?.protocolSamples(activity::fields)
        var reportedPhase = ReplyPhase.WAITING
        var deliveredText = ""
        suspend fun deliver(text: String) {
            if (text == deliveredText) return
            if (trace != null) trace.consume { onDelta(text) } else onDelta(text)
            deliveredText = text
        }
        val builder = client.newBuilder().readTimeout(p.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (logs != null) builder.eventListener(HttpTraceListener(logs, id, session, "model", operation, attempt))
        val call = builder.build().newCall(request)
        val started = System.nanoTime()
        fun duration() = trace?.elapsedMs() ?: ((System.nanoTime() - started) / 1_000_000)
        var outcome = "cancelled"
        logs?.event("api.start", mapOf("request" to id, "operation" to operation, "attempt" to attempt, "session" to session, "host" to request.url.host, "protocol" to p.protocol.name, "model" to p.model,
            "maxTokens" to p.maxTokens, "effort" to p.reasoningEffort, "thinking" to p.thinking))
        val watcher = launch(Dispatchers.IO) { try { awaitCancellation() } finally { call.cancel() } }
        val activeContext = currentCoroutineContext()
        val media = GeneratedMedia(mediaDirectory?.invoke(session) ?: File(temporaryDirectory, "images/$operation")) { activeContext.ensureActive() }
        val decoder = MediaJson(media) { activeContext.ensureActive() }
        fun images(): List<Attachment> = media.outputs().also { attachments -> if (attachments.isNotEmpty()) logs?.event("api.image_result", mapOf(
            "request" to id, "session" to session, "route" to "native", "reported" to attachments.first().output?.json(), "images" to attachments.map {
                mapOf("file" to it.name, "width" to it.width, "height" to it.height, "bytes" to it.size) })) }
        try {
            trace?.stage("awaiting_headers")
            call.await(trace).use { response ->
                trace?.resumed()
                if (response.isSuccessful) onStarted()
                if (CodexWire.isCodex(p)) {
                    try { privateModels?.response(request, response) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { logs?.error("codex.response_metadata.failed", e) }
                }
                if (!response.isSuccessful) {
                    val text = withContext(Dispatchers.IO) { response.peekBody(4096).string() }
                    throw ApiFailure(response.code, "请求失败 ${response.code}：${errorText(text)}")
                }
                // The provider has accepted this execution, including native image generation.
                // Replaying a successful response after a read failure can incur another charge.
                val original = requireNotNull(response.body).source()
                val source = trace?.source(original)?.buffer() ?: original
                val contentType = response.header("Content-Type")
                val streaming = StreamFraming.isEventStream(contentType, source)
                logs?.event("api.response_format", mapOf("request" to id, "contentType" to contentType?.take(160),
                    "streaming" to streaming, "inferred" to (streaming && !contentType?.substringBefore(';')?.trim().equals("text/event-stream", true))))
                if (!streaming) {
                    val decoded = decoder.read(source, temporaryDirectory)
                    val result = ProtocolCodec.decodeJson(p.protocol, decoded).copy(attachments = images())
                    logs?.event("api.end", mapOf("request" to id, "ms" to duration(), "stream" to false,
                        "chars" to result.text.length, "tools" to result.calls.size, "inputTokens" to result.inputTokens, "outputTokens" to result.outputTokens, "reasoningTokens" to result.reasoningTokens, "stopReason" to result.stopReason))
                    deliver(result.text)
                    outcome = "done"
                    return@coroutineScope AttemptResult(result, id, System.nanoTime())
                }
                val accumulator = StreamAccumulator(p.protocol)
                var events = 0
                withContext(Dispatchers.IO) {
                    var lastUi = 0L
                    SseReader(source, temporaryDirectory).read({ accumulator.complete }, onStarted) { name, payload ->
                        val small = payload.smallText()
                        if (small == "[DONE]") accumulator.accept(name, small)
                        else if (payload.size > 0) {
                            val event = decoder.parse(payload::input)
                            activity.observe(name, event)
                            accumulator.accept(name, event)
                            if (reportedPhase != activity.phase) {
                                reportedPhase = activity.phase
                                logs?.event("api.semantic_phase", activity.fields() + mapOf("request" to id, "session" to session))
                                onPhase(reportedPhase)
                            }
                        }
                        events++; trace?.parsed()
                        if (payload.spilled) logs?.event("api.large_event", mapOf("request" to id, "eventName" to name, "bytes" to payload.size, "images" to media.attachments.size))
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 60 || accumulator.complete) {
                            deliver(accumulator.text())
                            lastUi = now
                        }
                    }
                }
                val result = accumulator.result().copy(attachments = images())
                deliver(result.text)
                logs?.event("api.end", mapOf("request" to id, "ms" to duration(), "events" to events,
                    "chars" to result.text.length, "tools" to result.calls.size, "inputTokens" to result.inputTokens, "outputTokens" to result.outputTokens, "reasoningTokens" to result.reasoningTokens, "stopReason" to result.stopReason))
                outcome = "done"
                AttemptResult(result, id, System.nanoTime())
            }
        } catch (e: Exception) {
            // OkHttp wakes a cancelled blocking read with SocketException. Preserve coroutine cancellation.
            currentCoroutineContext().ensureActive()
            outcome = "failed"
            logs?.error("api.failed", e, mapOf("request" to id, "ms" to duration()) + activity.fields() + if (e is ModelIncomplete) mapOf("reason" to e.reason, "outputTokens" to e.partial.outputTokens, "inputTokens" to e.partial.inputTokens, "reasoningTokens" to e.partial.reasoningTokens, "partialChars" to e.partial.text.length, "partialTools" to e.partial.calls.size, "partialToolArgumentChars" to e.partial.calls.sumOf { it.arguments.length }) else emptyMap())
            if (e is ModelIncomplete) throw ModelIncomplete(e.reason, e.partial.copy(attachments = images()))
            if (media.attachments.isNotEmpty()) throw ApiOutputFailure("图片已保留，但接口未完整结束：${Redactor.clean(e.message.orEmpty()).take(300)}", ApiResult("", attachments = images()))
            throw e
        } finally { trace?.finish(outcome); watcher.cancel(); call.cancel() }
    } }
    private fun errorText(text: String): String = Redactor.clean(runCatching {
        JSONObject(text).optJSONObject("error")?.optString("message") ?: JSONObject(text).optString("message", text.take(300))
    }.getOrElse { text.take(300) })
}
class ApiFailure(val code: Int, message: String) : IOException(message) {
    val retryable = code in listOf(429, 502, 503, 504) && !message.contains("only supported on", true) &&
        !message.contains("unsupported model", true) && !message.contains("model_not_found", true)
}
