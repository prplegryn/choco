package app.chocola.data

data class TextEdit(val text: String, val start: Int, val end: Int = start)

/** Selection-aware, Unicode-preserving Markdown editing shared by the toolbar and tests. */
object MarkdownEditing {
    fun heading(text: String, start: Int, end: Int, level: Int): TextEdit {
        require(level in 0..6)
        val a = minOf(start, end).coerceIn(0, text.length); val b = maxOf(start, end).coerceIn(a, text.length)
        val first = if (a == 0) 0 else text.lastIndexOf('\n', a - 1) + 1
        val last = text.indexOf('\n', b).takeIf { it >= 0 } ?: text.length
        val result = text.substring(first, last).split('\n').joinToString("\n") { line ->
            val plain = line.replaceFirst(Regex("^#{1,6}\\s+"), "")
            if (level == 0) plain else "#".repeat(level) + " " + plain
        }
        return TextEdit(text.replaceRange(first, last, result), first, first + result.length)
    }
    fun wrap(text: String, start: Int, end: Int, before: String, after: String = before, placeholder: String = "文字"): TextEdit {
        val a = minOf(start, end).coerceIn(0, text.length); val b = maxOf(start, end).coerceIn(a, text.length)
        val selected = text.substring(a, b).ifEmpty { placeholder }
        if (a >= before.length && text.substring(a - before.length, a) == before && text.substring(b).startsWith(after)) {
            return TextEdit(text.removeRange(b, b + after.length).removeRange(a - before.length, a), a - before.length, b - before.length)
        }
        return TextEdit(text.replaceRange(a, b, before + selected + after), a + before.length, a + before.length + selected.length)
    }
    fun line(text: String, start: Int, end: Int, prefix: String): TextEdit {
        val a = minOf(start, end).coerceIn(0, text.length); val b = maxOf(start, end).coerceIn(a, text.length)
        val first = text.lastIndexOf('\n', (a - 1).coerceAtLeast(0)).let { if (a == 0 || it < 0) 0 else it + 1 }
        val last = text.indexOf('\n', b).takeIf { it >= 0 } ?: text.length
        val selected = text.substring(first, last)
        val lines = selected.split('\n'); val remove = lines.all { it.startsWith(prefix) }
        val result = lines.joinToString("\n") { if (remove) it.removePrefix(prefix) else prefix + it }
        return TextEdit(text.replaceRange(first, last, result), first, first + result.length)
    }
    fun insert(text: String, start: Int, end: Int, value: String): TextEdit {
        val a = minOf(start, end).coerceIn(0, text.length); val b = maxOf(start, end).coerceIn(a, text.length)
        return TextEdit(text.replaceRange(a, b, value), a + value.length)
    }
}
