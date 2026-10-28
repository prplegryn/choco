package app.chocola.data

import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.ceil

/** Shared by protocol construction and budgeting. File reads belong on an IO dispatcher. */
object AttachmentInputs {
    const val TextLimit = 60_000
    private data class Cached(val stamp: Long, val size: Long, val text: String)
    private val cache = object : LinkedHashMap<String, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 48
    }
    private fun excerpt(a: Attachment): String {
        val file = File(a.path)
        val stamp = file.lastModified(); val size = file.length()
        synchronized(cache) { cache[a.path]?.takeIf { it.stamp == stamp && it.size == size }?.let { return it.text } }
        val content = runCatching {
            file.reader(Charsets.UTF_8).use { reader ->
                val chars = CharArray(TextLimit + 1); var count = 0
                while (count < chars.size) { val n = reader.read(chars, count, chars.size - count); if (n < 0) break; if (n == 0) continue; count += n }
                val end = if (count > TextLimit && chars[TextLimit - 1].isHighSurrogate()) TextLimit - 1 else minOf(count, TextLimit)
                String(chars, 0, end) + if (count > TextLimit) "\n[附件正文已截断；可使用 read_file 继续读取完整文件]" else ""
            }
        }.getOrElse { "[附件暂不可读，请重新添加文件]" }
        synchronized(cache) { cache[a.path] = Cached(stamp, size, content) }
        return content
    }
    fun text(message: Message): String = buildString {
        append(ImageReferences.wireText(message))
        message.attachments.filter { it.mime.startsWith("image/") }.forEach { append("\n"); append(reference(it)) }
        message.attachments.filterNot { it.mime.startsWith("image/") }.forEach { a ->
            append("\n\n"); append(reference(a)); append('\n')
            val readable = a.mime.startsWith("text/") || a.mime.contains("json") || a.mime.contains("xml") ||
                File(a.path).extension.lowercase() in listOf("md", "py", "kt", "js", "ts", "sh", "csv", "yaml", "yml", "toml", "log")
            append(if (readable) excerpt(a) else "[二进制附件；可使用工具读取]")
        }
    }
    fun reference(a: Attachment): String = "[" + (if (a.imageLabel.isNotBlank()) "${a.imageLabel} 对应" else "") + "文件 ${a.name}，路径 /workspace/${File(a.path).parentFile?.name}/${File(a.path).name}" +
        (if (a.width > 0 && a.height > 0) "，实际尺寸 ${a.width}×${a.height}" else "") + "]"
    /** A portable safety allowance; actual provider usage replaces estimates after the reply. */
    fun imageTokens(a: Attachment): Int {
        val tiles = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(a.path, bounds)
            ceil(bounds.outWidth.coerceAtLeast(1) / 512.0) * ceil(bounds.outHeight.coerceAtLeast(1) / 512.0)
        }.getOrDefault(16.0)
        return (tiles * 256).toInt().coerceIn(4096, 32768)
    }
    fun summarized(message: Message): Message {
        val files = message.attachments.filterNot { it.mime.startsWith("image/") }
        if (files.isEmpty()) return message
        return message.copy(text = message.text + "\n\n[此前文本附件内容已整理进上下文摘要]\n" + files.joinToString("\n", transform = ::reference),
            attachments = message.attachments.filter { it.mime.startsWith("image/") })
    }
}
