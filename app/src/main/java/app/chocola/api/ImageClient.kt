package app.chocola.api

import app.chocola.data.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.*
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ImageGenerationFailure(message: String, val partial: List<Attachment>) : IllegalStateException(message)
class ImageGenerationCancelled(val partial: List<Attachment>, cause: CancellationException) : CancellationException(cause.message) {
    init { initCause(cause) }
}
internal data class ImageResult(val images: List<Attachment>, val model: String)

internal class ImageClient(private val logs: Diagnostics? = null, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
    private val privateModels: PrivateModelAccess? = null) {
    suspend fun generate(provider: Provider, settings: ImageSettings, prompt: String, references: List<Attachment>, directory: File,
        temporary: File, session: String, routingModel: String = "", onImages: suspend (List<Attachment>) -> Unit = {}): ImageResult = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank() && prompt.length <= 32_000) { "绘图描述应为 1 到 32000 个字符" }
        require(references.size <= 5 && references.all { it.mime in setOf("image/png", "image/jpeg", "image/webp") }) { "参考图最多 5 张，支持 PNG、JPEG、WebP" }
        require(references.all { File(it.path).isFile && File(it.path).length() in 1..GeneratedMedia.MaxImageBytes } && references.sumOf { File(it.path).length() } <= 96L * 1024 * 1024) { "参考图无法读取或超过大小限制" }
        ImageModels.validate(settings, provider.model)
        val span = logs?.begin("image", session = session, fields = mapOf("model" to provider.model, "transport" to settings.transport,
            "references" to references.size, "size" to settings.size, "quality" to settings.quality, "count" to settings.count, "format" to settings.format))
        val active = currentCoroutineContext()
        val completed = mutableListOf<Attachment>()
        var currentMedia: GeneratedMedia? = null
        val requested = ImageOutput(route = settings.transport, requestedModel = provider.model, requestedSize = settings.size, requestedQuality = settings.quality)
        fun outputs() = completed.toList() + currentMedia?.outputs(requested).orEmpty()
        var reported = 0
        suspend fun publish() { val images = outputs(); if (images.size != reported) { reported = images.size; onImages(images) } }
        val codex = CodexWire.isCodex(provider)
        var outcome = "failed"
        try {
            val responses = settings.transport == "responses"
            // A batch reserves one independent generation per slot; completed slots are never replayed.
            val iterations = settings.count
            repeat(iterations) {
                active.ensureActive()
                // Deduplicate repeated SSE events within a request, never across independent slots.
                val media = GeneratedMedia(directory) { active.ensureActive() }
                currentMedia = media
                val decoder = MediaJson(media) { active.ensureActive() }
                val callSettings = settings.copy(count = 1)
                val slotPrompt = if (settings.count == 1) prompt else "$prompt\n\n本批共 ${settings.count} 张独立图片。本次只生成第 ${it + 1} 张，对应上述要求的第 ${it + 1} 个画面；只输出一张独立图片，不要把多张画面拼在一起。"
                val body = if (responses) responsesBody(provider, callSettings, slotPrompt, references, routingModel)
                    else if (references.isEmpty()) ImageRequest.body(provider, callSettings, slotPrompt).apply { if (codex) put("stream", true) }.toString().toRequestBody(Json)
                    else if (codex) nativeEditBody(provider, callSettings, slotPrompt, references)
                    else MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                        val params = ImageRequest.body(provider, callSettings, slotPrompt, edit = true).apply { if (codex) put("stream", true) }
                        params.keys().forEach { key -> addFormDataPart(key, params.get(key).toString()) }
                        references.forEach { image -> addFormDataPart(if (references.size > 1) "image[]" else "image", image.name, File(image.path).asRequestBody(image.mime.toMediaType())) }
                    }.build()
                val url = ImageRequest.endpoint(provider, references.isNotEmpty(), responses)
                val unsigned = request(provider, url).post(body).build()
                var attempt = 0
                while (true) {
                    val request = if (codex) requireNotNull(privateModels) { "Codex 绘图接入未初始化" }.imageRequest(provider, session, unsigned) else unsigned
                    span?.stage("request", mapOf("index" to it + 1), record = true)
                    val transport = client.newBuilder().readTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                        .callTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS).build().traced(logs, "image", session)
                    val call = transport.newCall(request)
                    val cancellation = launch(Dispatchers.IO) { try { awaitCancellation() } finally { call.cancel() } }
                    var retryRejected = false
                    try { call.await().use { response ->
                        if (codex) {
                            try { privateModels?.response(request, response) }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) { logs?.error("codex.image_metadata.failed", error) }
                        }
                        if (!response.isSuccessful) {
                            if (codex && response.code in setOf(401, 429) && attempt < 2) {
                                retryRejected = true; attempt++
                                span?.event("image.preflight_retry", mapOf("attempt" to attempt, "status" to response.code))
                                return@use
                            }
                            val detail = response.peekBody(4096).string()
                            val message = HttpErrorText.decode(detail).ifBlank { "HTTP ${response.code}" }
                            throw ApiFailure(response.code, "绘图请求失败：${Redactor.clean(message).take(1000)}")
                        }
                        span?.stage("reading", record = true)
                        val source = requireNotNull(response.body).source()
                        if (StreamFraming.isEventStream(response.header("Content-Type"), source)) {
                            var complete = false
                            SseReader(source, temporary).read({ complete }) { event, payload ->
                                if (payload.smallText() == "[DONE]") complete = true
                                else if (payload.size > 0) {
                                    val json = decoder.parse(payload::input)
                                    checkError(json)
                                    val type = json.optString("type", event)
                                    if (type == "response.completed" || type == "response.done" || type.endsWith(".completed") && media.attachments.isNotEmpty()) complete = true
                                    if (type.contains("generating") || type.contains("partial_image")) span?.stage("generating")
                                    urls(json).forEach { address -> download(address, provider, transport, media) }
                                    publish()
                                    if (payload.spilled) span?.event("image.large_event", mapOf("bytes" to payload.size, "images" to media.attachments.size, "type" to type))
                                }
                            }
                            check(complete) { "绘图响应未完整结束，已完成的图片已保留" }
                        } else {
                            val json = decoder.read(source, temporary)
                            checkError(json)
                            urls(json).forEach { address -> download(address, provider, transport, media) }
                            publish()
                        }
                    } } finally { cancellation.cancel(); call.cancel() }
                    if (!retryRejected) break
                }
                require(media.attachments.isNotEmpty()) { "第 ${it + 1} 张未返回可用图片；此前已完成的图片已保留" }
                completed += media.outputs(requested)
                currentMedia = null
            }
            require(completed.size >= settings.count) { "请求了 ${settings.count} 张图片，接口只返回 ${completed.size} 张；已完成的图片已保留" }
            val images = outputs()
            logs?.event("image.result", mapOf("session" to session, "requested" to images.first().output?.json(), "images" to images.map {
                mapOf("file" to it.name, "width" to it.width, "height" to it.height, "bytes" to it.size, "sizeMismatch" to it.imageSizeMismatch) }))
            outcome = "done"
            ImageResult(images, provider.model)
        } catch (error: CancellationException) { outcome = "cancelled"; throw ImageGenerationCancelled(outputs(), error) }
        catch (error: Exception) {
            if (!active.isActive) {
                outcome = "cancelled"
                throw ImageGenerationCancelled(outputs(), CancellationException("绘图已停止").apply { initCause(error) })
            }
            logs?.error("image.failed", error, mapOf("session" to session, "receivedImages" to outputs().size))
            throw ImageGenerationFailure(Redactor.clean(error.message ?: "绘图失败"), outputs())
        } finally { span?.finish(outcome, mapOf("images" to outputs().size, "bytes" to outputs().sumOf { it.size })) }
    }
    private fun urls(json: JSONObject): List<String> = json.optJSONArray("data")?.objects().orEmpty().mapNotNull {
        it.optString("url").takeIf { value -> value.isNotBlank() }
    }
    private fun checkError(json: JSONObject) {
        val failure = json.optJSONObject("error") ?: json.optJSONObject("response")?.optJSONObject("error")
        if (failure != null) throw IllegalStateException(failure.optString("message", "图片接口返回错误").take(1000))
        if (json.optString("type") in setOf("response.failed", "response.incomplete", "error")) error("图片接口未完成生成")
    }
    private suspend fun download(address: String, provider: Provider, transport: OkHttpClient, media: GeneratedMedia) = coroutineScope {
        val uri = runCatching { URI(address) }.getOrNull() ?: error("图片下载地址无效")
        val origin = URI(provider.baseUrl)
        require(uri.userInfo == null && (uri.scheme == "https" || uri.scheme == "http" && uri.host == origin.host && uri.port == origin.port)) { "图片下载地址需要 HTTPS，或与当前 API 同源" }
        // A result URL never receives API keys, custom authorization headers, or redirects.
        val call = transport.newCall(Request.Builder().url(address).build())
        val cancellation = launch(Dispatchers.IO) { try { awaitCancellation() } finally { call.cancel() } }
        try { call.await().use { response ->
            require(response.isSuccessful) { "图片下载失败，HTTP ${response.code}" }
            requireNotNull(response.body).byteStream().use(media::copy)
        } } finally { cancellation.cancel(); call.cancel() }
    }
    private fun request(provider: Provider, url: String): Request.Builder = Request.Builder().url(url).apply {
        Redactor.register(provider.apiKey); provider.headers.values.forEach(Redactor::register)
        if (provider.apiKey.isNotBlank()) header("Authorization", "Bearer ${provider.apiKey}")
        provider.headers.forEach { (key, value) -> header(key, value) }
        removeHeader("Content-Type"); header("Accept", "application/json, text/event-stream")
    }
    /** Codex's native edit route consumes JSON; its reference array is streamed from files. */
    private fun nativeEditBody(provider: Provider, settings: ImageSettings, prompt: String, references: List<Attachment>): RequestBody {
        val params = ImageRequest.body(provider, settings, prompt, edit = true).put("stream", true).toString().dropLast(1)
        return object : RequestBody() {
            override fun contentType() = Json
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8(params + ",\"images\":[")
                references.forEachIndexed { index, image ->
                    if (index > 0) sink.writeUtf8(",")
                    sink.writeUtf8("{\"image_url\":\"")
                    writeReference(sink, image)
                    sink.writeUtf8("\"}")
                }
                sink.writeUtf8("]}")
            }
        }
    }
    private fun writeReference(sink: BufferedSink, image: Attachment) {
        sink.writeUtf8("data:${image.mime};base64,")
        val unclosed = object : FilterOutputStream(sink.outputStream()) { override fun close() { flush() } }
        Base64.getEncoder().wrap(unclosed).use { encoded -> File(image.path).inputStream().use { it.copyTo(encoded, 8192) } }
    }
    private fun responsesBody(provider: Provider, settings: ImageSettings, prompt: String, references: List<Attachment>, routingModel: String): RequestBody {
        val original = ImageRequest.responseBody(provider, settings, prompt, routingModel, references.isNotEmpty())
        val json = if (CodexWire.isCodex(provider)) CodexWire.normalize(original) else original
        if (references.isEmpty()) return json.toString().toRequestBody(Json)
        json.remove("input")
        return object : RequestBody() {
            override fun contentType() = Json
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8(json.toString().dropLast(1))
                sink.writeUtf8(",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":" + JSONObject.quote(prompt) + "}")
                references.forEach { image ->
                    sink.writeUtf8(",{\"type\":\"input_image\",\"image_url\":\"")
                    writeReference(sink, image)
                    sink.writeUtf8("\"}")
                }
                sink.writeUtf8("]}]}")
            }
        }
    }
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> client.dispatcher.executorService.execute { value.close() } } else response.close()
            }
        })
    }
    companion object { private val Json = "application/json; charset=utf-8".toMediaType() }
}
