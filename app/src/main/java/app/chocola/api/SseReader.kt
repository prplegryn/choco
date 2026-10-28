package app.chocola.api

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.io.*

/** Some gateways omit or mislabel Content-Type. Inspect bounded framing without consuming payload. */
internal object StreamFraming {
    fun isEventStream(contentType: String?, source: BufferedSource): Boolean {
        if (contentType?.substringBefore(';')?.trim().equals("text/event-stream", ignoreCase = true)) return true
        val peek = source.peek()
        if (!peek.request(1)) return false
        if (peek.buffer[0] == 0xef.toByte() && peek.request(3) && peek.buffer[1] == 0xbb.toByte() && peek.buffer[2] == 0xbf.toByte()) peek.skip(3)
        repeat(256) {
            if (!peek.request(1)) return false
            when (peek.buffer[0].toInt() and 255) {
                9, 10, 13, 32 -> peek.skip(1)
                58 -> return true
                else -> return listOf("data:", "event:", "id:", "retry:").any { peek.rangeEquals(0, it.encodeUtf8()) }
            }
        }
        return false
    }
}

/** Framing is bounded on disk, not by the length of one base64 image line in heap. */
internal class SsePayload(private val directory: File, private val limit: Long = 128L * 1024 * 1024) : AutoCloseable {
    private var memory: ByteArrayOutputStream? = ByteArrayOutputStream()
    private var file: File? = null
    private var output: OutputStream? = null
    private var frozen: ByteArray? = null
    var size = 0L
        private set
    val spilled get() = file != null
    fun write(buffer: Buffer, count: Long) {
        require(size + count <= limit) { "单个响应事件超过 128 MB，已停止读取；请减少单次图片数量" }
        if (file == null && size + count > 65_536) {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建响应临时目录" }
            val target = File.createTempFile("event-", ".part", directory)
            file = target
            output = target.outputStream().buffered().also { requireNotNull(memory).writeTo(it) }
            memory = null
        }
        buffer.writeTo(output ?: requireNotNull(memory), count); size += count
    }
    fun newline() { val line = Buffer().writeByte(10); write(line, 1) }
    fun seal() { output?.close(); output = null; if (file == null && frozen == null) { frozen = requireNotNull(memory).toByteArray(); memory = null } }
    fun input(): InputStream { seal(); return file?.inputStream() ?: ByteArrayInputStream(requireNotNull(frozen)) }
    fun smallText(): String? = if (size <= 64) input().use { it.readBytes().toString(Charsets.UTF_8) } else null
    override fun close() { runCatching { output?.close() }; file?.delete(); memory = null; frozen = null }
}

/** Supports LF, CRLF, CR, UTF-8 BOM, comments and multiline data; never logs payload bytes. */
internal class SseReader(private val source: BufferedSource, private val directory: File, private val eventLimit: Long = 128L * 1024 * 1024) {
    private val newline = "\r\n".encodeUtf8()
    suspend fun read(stop: () -> Boolean, onData: () -> Unit = {}, onEvent: suspend (String, SsePayload) -> Unit) {
        var payload = SsePayload(directory, eventLimit)
        var event = ""
        var dataLines = 0
        var first = true
        var skipLf = false
        try {
            while (!stop() && source.request(1)) {
                currentCoroutineContext().ensureActive()
                if (skipLf) {
                    skipLf = false
                    if (source.buffer[0] == 10.toByte()) { source.skip(1); if (!source.request(1)) break }
                }
                if (first) {
                    first = false
                    if (source.buffer[0] == 0xef.toByte() && source.request(3) && source.buffer[1] == 0xbb.toByte() && source.buffer[2] == 0xbf.toByte()) source.skip(3)
                }
                // Field names are short ASCII; unknown or excessive names are discarded.
                val field = StringBuilder()
                var hasColon = false
                var blank = true
                while (source.request(1)) {
                    val byte = source.buffer[0].toInt() and 255
                    if (byte == 10 || byte == 13) break
                    blank = false
                    source.skip(1)
                    if (byte == 58) { hasColon = true; break }
                    if (field.length < 32) field.append(byte.toChar())
                }
                val name = field.toString()
                if (hasColon && source.request(1) && source.buffer[0] == 32.toByte()) source.skip(1)
                val eventName = if (name == "event") ByteArrayOutputStream() else null
                if (name == "data") { onData(); if (dataLines++ > 0) payload.newline() }
                while (source.request(1)) {
                    currentCoroutineContext().ensureActive()
                    val at = source.buffer.indexOfElement(newline)
                    val count = if (at >= 0) at else source.buffer.size
                    if (count > 0) {
                        when {
                            name == "data" -> payload.write(source.buffer, count)
                            eventName != null && eventName.size() + count <= 256 -> source.buffer.writeTo(eventName, count)
                            else -> source.skip(count)
                        }
                    }
                    if (at >= 0) break
                }
                if (source.request(1)) {
                    val ending = source.readByte()
                    // Dispatch CR-terminated events immediately; consume a following LF next time.
                    skipLf = ending == 13.toByte()
                }
                if (eventName != null) event = eventName.toString("UTF-8")
                if (blank) {
                    if (dataLines > 0) { payload.seal(); onEvent(event, payload) }
                    payload.close(); payload = SsePayload(directory, eventLimit); event = ""; dataLines = 0
                }
            }
            if (!stop() && dataLines > 0) { payload.seal(); onEvent(event, payload) }
        } finally { payload.close() }
    }
}
