package app.chocola.api

import app.chocola.data.*
import com.fasterxml.jackson.core.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest

/** Image bytes live in the conversation workspace; JSON and encrypted chat records contain references. */
internal class GeneratedMedia(private val directory: File, private val checkActive: () -> Unit = {}) {
    companion object { const val MaxImageBytes = 32L * 1024 * 1024 }
    private val saved = linkedMapOf<String, Attachment>()
    private var reported = ImageOutput()
    val attachments get() = saved.values.toList()
    fun outputs(requested: ImageOutput = ImageOutput(route = "native")) = attachments.map { image -> image.copy(output = requested.copy(
        reportedModel = reported.reportedModel, reportedSize = reported.reportedSize, reportedQuality = reported.reportedQuality)) }
    fun observe(json: JSONObject) {
        fun capture(node: JSONObject) {
            reported = reported.copy(reportedModel = node.optString("model").takeIf { it.isNotBlank() && it != "null" }?.take(128) ?: reported.reportedModel,
                reportedSize = node.optString("size").takeIf { it.isNotBlank() && it != "null" }?.take(64) ?: reported.reportedSize,
                reportedQuality = node.optString("quality").takeIf { it.isNotBlank() && it != "null" }?.take(64) ?: reported.reportedQuality)
        }
        if (json.optJSONArray("data") != null || json.optString("type").startsWith("image_generation.")) capture(json)
        val response = json.optJSONObject("response") ?: json
        response.optJSONArray("tools")?.objects()?.filter { it.optString("type") == "image_generation" }?.forEach(::capture)
        (response.optJSONArray("output")?.objects().orEmpty() + listOfNotNull(json.optJSONObject("item"))).filter { it.optString("type") == "image_generation_call" }.forEach(::capture)
    }
    fun decode(parser: JsonParser): Attachment = write { sink -> parser.readBinaryValue(Base64Variants.MIME_NO_LINEFEEDS, sink) }
    fun copy(input: InputStream): Attachment = write { sink -> input.copyTo(sink, 8192) }
    private fun write(block: (OutputStream) -> Unit): Attachment {
        checkActive()
        check(directory.isDirectory || directory.mkdirs()) { "无法保存生成的图片" }
        val partial = File.createTempFile("image-", ".part", directory)
        var bytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            partial.outputStream().buffered().use { file ->
                val bounded = object : OutputStream() {
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                    override fun write(value: ByteArray, start: Int, count: Int) {
                        checkActive(); require(bytes + count <= MaxImageBytes) { "单张图片超过 32 MB，请减小尺寸后重试" }
                        digest.update(value, start, count); file.write(value, start, count); bytes += count
                    }
                }
                block(bounded)
            }
            val header = ByteArray(12)
            val read = partial.inputStream().use { it.read(header) }
            val (mime, extension) = when {
                read >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png" to "png"
                read >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "image/jpeg" to "jpg"
                read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp" to "webp"
                else -> error("接口返回的图片不是有效的 PNG、JPEG 或 WebP")
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(directory, "image-${hash.take(24)}.$extension")
            require(target.path in saved || saved.size < 10) { "单次图片数量超过 10 张" }
            if (target.exists()) partial.delete() else check(partial.renameTo(target)) { "无法提交生成的图片" }
            val dimensions = ImageDimensions.read(target, mime)
            val attachment = Attachment(target.name, target.absolutePath, mime, bytes, dimensions.first, dimensions.second)
            saved[target.path] = attachment
            return attachment
        } finally { partial.delete() }
    }
}

/** Two passes allow `type` to occur after `result`, without buffering the result string. */
internal class MediaJson(private val media: GeneratedMedia, private val checkActive: () -> Unit = {}) {
    private data class Meta(var type: String = "")
    private val factory = JsonFactory().apply {
        setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(48 * 1024 * 1024)
            .maxNameLength(1024).maxNumberLength(128).maxDocumentLength(128L * 1024 * 1024).build())
        disable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)
    }
    fun parse(open: () -> InputStream): JSONObject {
        val metadata = hashMapOf<List<String>, Meta>()
        try {
            parser(open).use { p -> require(p.nextToken() == JsonToken.START_OBJECT); scan(p, emptyList(), metadata) }
            return parser(open).use { p -> p.nextToken(); val value = value(p, emptyList(), metadata); require(p.nextToken() == null); (value as JSONObject).also(media::observe) }
        } catch (error: CancellationException) { throw error }
        catch (error: IllegalArgumentException) { throw error }
        catch (error: IllegalStateException) { throw error }
        catch (error: Exception) { throw IllegalStateException("接口响应格式无法读取（${error.javaClass.simpleName}）") }
    }
    suspend fun read(source: okio.BufferedSource, temporary: File): JSONObject = SsePayload(temporary).use { payload ->
        while (source.request(1)) { checkActive(); payload.write(source.buffer, source.buffer.size) }
        payload.seal(); parse(payload::input)
    }
    private fun parser(open: () -> InputStream): JsonParser = factory.createParser(object : FilterInputStream(open()) {
        override fun read(): Int { checkActive(); return super.read() }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int { checkActive(); return super.read(bytes, offset, count) }
    })
    private fun scan(p: JsonParser, path: List<String>, metadata: MutableMap<List<String>, Meta>) {
        checkActive()
        when (p.currentToken()) {
            JsonToken.START_OBJECT -> {
                val meta = Meta()
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    require(p.currentToken() == JsonToken.FIELD_NAME)
                    val key = p.currentName; require(p.nextToken() != null)
                    if (key == "type" && p.currentToken() == JsonToken.VALUE_STRING) meta.type = p.text
                    else scan(p, path + key, metadata)
                }
                if (meta.type == "image_generation_call" || meta.type.contains("partial_image")) metadata[path] = meta
            }
            JsonToken.START_ARRAY -> {
                var index = 0
                while (p.nextToken() != JsonToken.END_ARRAY) { require(p.currentToken() != null); scan(p, path + (index++).toString(), metadata) }
            }
            else -> Unit // nextToken skips unopened strings directly, including megabytes of base64.
        }
    }
    private fun value(p: JsonParser, path: List<String>, metadata: Map<List<String>, Meta>): Any {
        checkActive()
        return when (p.currentToken()) {
            JsonToken.START_OBJECT -> JSONObject().also { json ->
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    require(p.currentToken() == JsonToken.FIELD_NAME)
                    val key = p.currentName; require(p.nextToken() != null)
                    val image = (key == "result" && metadata[path]?.type == "image_generation_call") || key == "b64_json"
                    when {
                        key == "partial_image_b64" || (key == "b64_json" && metadata[path]?.type?.contains("partial_image") == true) -> { p.skipChildren(); json.put(key, JSONObject.NULL) }
                        image && p.currentToken() == JsonToken.VALUE_STRING -> { media.decode(p); json.put(key, JSONObject.NULL) }
                        else -> json.put(key, value(p, path + key, metadata))
                    }
                }
            }
            JsonToken.START_ARRAY -> JSONArray().also { array ->
                var index = 0
                while (p.nextToken() != JsonToken.END_ARRAY) { require(p.currentToken() != null); array.put(value(p, path + (index++).toString(), metadata)) }
            }
            JsonToken.VALUE_STRING -> p.text.also { require(it.length <= 8_000_000) { "接口的非图片文本超过限制" } }
            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> p.numberValue
            JsonToken.VALUE_TRUE -> true
            JsonToken.VALUE_FALSE -> false
            JsonToken.VALUE_NULL -> JSONObject.NULL
            else -> error("接口返回了不完整的 JSON 数据")
        }
    }
}
