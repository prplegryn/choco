package app.chocola.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.appendInlineContent

@Immutable internal data class PreparedBlock(val block: MarkdownBlock, val content: AnnotatedString, val cells: List<List<AnnotatedString>> = emptyList(), val streaming: Boolean = false)

/** Bounded, thread-safe preparation cache. Called by the document projector on Default. */
internal object RichDocuments {
    private val cache = LinkedHashMap<Pair<String, Boolean>, AnnotatedString>(64, .75f, true)
    private var characters = 0
    @Synchronized fun inline(text: String, streaming: Boolean): AnnotatedString {
        val key = text to streaming
        cache[key]?.let { return it }
        val result = InlineDocument.parse(ProseLayout.softBreaks(text), streaming)
        if (text.length < 24000) {
            cache[key] = result; characters += text.length
            val oldest = cache.entries.iterator()
            while ((characters > 200000 || cache.size > 512) && oldest.hasNext()) { characters -= oldest.next().key.first.length; oldest.remove() }
        }
        return result
    }
    fun prepare(block: MarkdownBlock, streaming: Boolean): PreparedBlock = PreparedBlock(block,
        if (block.type in setOf(MarkdownType.CODE, MarkdownType.MATH)) AnnotatedString(block.text) else inline(block.text, streaming),
        block.table?.rows?.map { row -> row.map { inline(it, streaming) } }.orEmpty(), streaming).also(MathTypesetter::prepare)
}

/** Tolerates an open streaming delimiter without repeatedly flashing Markdown punctuation. */
internal object InlineDocument {
    fun parse(text: String, streaming: Boolean): AnnotatedString = buildAnnotatedString {
        fun closing(marker: String, start: Int, end: Int): Int {
            var found = text.indexOf(marker, start)
            while (found >= 0 && found < end) {
                var slashes = 0; var before = found - 1
                while (before >= 0 && text[before--] == '\\') slashes++
                if (slashes % 2 == 0) return found
                found = text.indexOf(marker, found + marker.length)
            }
            return -1
        }
        fun emit(start: Int, end: Int, depth: Int) {
            if (depth > 8) { append(text.substring(start, end)); return }
            var i = start
            while (i < end) {
                val c = text[i]
                val math = MathSyntax.inlineAt(text, i, end, pending = streaming)
                if (math != null) {
                    val id = "math:$i"
                    pushStringAnnotation(MathSyntax.Tag, id)
                    appendInlineContent(id, math.expression.source)
                    pop()
                    i = math.end; continue
                }
                if (!streaming && text.startsWith("\\(", i)) {
                    val unfinished = MathSyntax.inlineAt(text, i, end, pending = true)
                    if (unfinished != null && !unfinished.expression.complete) { append(unfinished.expression.source); i = unfinished.end; continue }
                }
                if (c == '\\' && i + 1 < end && text[i + 1] in "\\`*_[]()~$") { append(text[i + 1]); i += 2; continue }
                if (c == '`') {
                    var count = 1
                    while (i + count < end && text[i + count] == '`') count++
                    val marker = "`".repeat(count)
                    val close = closing(marker, i + count, end)
                    if (close >= 0 || streaming) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = .96f.em)) {
                            append(text.substring(i + count, if (close < 0) end else close))
                        }
                        i = if (close < 0) end else close + count; continue
                    }
                }
                if (c == '[') {
                    val labelEnd = closing("]", i + 1, end)
                    val urlEnd = if (labelEnd >= 0 && text.getOrNull(labelEnd + 1) == '(') closing(")", labelEnd + 2, end) else -1
                    if (urlEnd >= 0) {
                        val url = text.substring(labelEnd + 2, urlEnd)
                        if (url.startsWith("https://") || url.startsWith("http://")) {
                            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Palette.Ink, textDecoration = TextDecoration.Underline)))) { emit(i + 1, labelEnd, depth + 1) }
                            i = urlEnd + 1; continue
                        }
                    } else if (streaming && (labelEnd < 0 || labelEnd + 1 == end || text.getOrNull(labelEnd + 1) == '(')) {
                        emit(i + 1, if (labelEnd < 0) end else labelEnd, depth + 1); i = end; continue
                    }
                }
                val marker = when {
                    text.startsWith("***", i) -> "***"
                    text.startsWith("**", i) -> "**"; text.startsWith("__", i) && (i == start || !text[i - 1].isLetterOrDigit()) -> "__"; text.startsWith("~~", i) -> "~~"
                    c == '*' -> "*"; c == '_' && (i == start || !text[i - 1].isLetterOrDigit()) -> "_"; else -> ""
                }
                if (streaming && marker.isNotEmpty() && i + marker.length == end) { i = end; continue }
                if (marker.isNotEmpty() && i + marker.length < end && !text[i + marker.length].isWhitespace()) {
                    val close = closing(marker, i + marker.length, end)
                    if (close >= 0 || streaming) {
                        val style = when (marker) {
                            "***" -> SpanStyle(fontWeight = FontWeight.Medium, fontStyle = FontStyle.Italic)
                            "**", "__" -> SpanStyle(fontWeight = FontWeight.Medium)
                            "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                            else -> SpanStyle(fontStyle = FontStyle.Italic)
                        }
                        withStyle(style) { emit(i + marker.length, if (close < 0) end else close, depth + 1) }
                        i = if (close < 0) end else close + marker.length; continue
                    }
                }
                append(c); i++
            }
        }
        emit(0, text.length, 0)
    }
}
