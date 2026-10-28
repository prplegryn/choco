package app.chocola.ui

internal data class MathExpression(val latex: String, val source: String, val display: Boolean, val complete: Boolean = true)
internal data class MathRange(val start: Int, val end: Int, val expression: MathExpression)

/** Math is tokenized before Markdown, so TeX underscores, pipes and row breaks stay intact. */
internal object MathSyntax {
    const val Tag = "choco.math"
    const val MaxLength = 32768
    private val environments = setOf("equation", "equation*", "align", "align*", "alignat", "alignat*", "aligned", "alignedat", "gather", "gather*", "gathered", "multline", "multline*", "split", "cases", "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix", "smallmatrix", "array")
    // Android ICU requires escaping literal closing braces as well as opening braces.
    private val begin = Regex("""\\begin\{([A-Za-z*]+)\}""")
    private val prohibited = Regex("""\\(?:input|include|includegraphics|read|write|openin|openout|catcode|def|gdef|edef|xdef|newcommand|renewcommand|providecommand|newenvironment|renewenvironment|csname|jlmDynamic|jlmExternalFont|DeclareMathSizes|magnification|definecolor)\b""")

    private fun escaped(text: String, at: Int): Boolean {
        var slash = at - 1
        while (slash >= 0 && text[slash] == '\\') slash--
        return (at - slash - 1) % 2 == 1
    }
    private fun close(text: String, marker: String, from: Int, end: Int): Int {
        var found = text.indexOf(marker, from)
        while (found >= 0 && found + marker.length <= end) {
            if (!escaped(text, found)) return found
            found = text.indexOf(marker, found + marker.length)
        }
        return -1
    }
    fun inlineAt(text: String, at: Int, end: Int = text.length, pending: Boolean = false): MathRange? {
        if (at >= end || (text[at] != '$' && text[at] != '\\') || escaped(text, at)) return null
        val parens = text.startsWith("\\(", at)
        if (!parens && (text[at] != '$' || text.getOrNull(at + 1) == '$' || (at > 0 && text[at - 1] == '$'))) return null
        val marker = if (parens) "\\)" else "$"
        val body = at + if (parens) 2 else 1
        if (!parens && text.getOrNull(body)?.isWhitespace() == true) return null
        val limit = minOf(end, body + MaxLength)
        val finish = close(text, marker, body, limit)
        if (!parens && finish >= 0) {
            // An invalid first closing dollar ends this candidate; never pair across another price.
            if (finish == body || text[finish - 1].isWhitespace() || text.getOrNull(finish + 1)?.isDigit() == true || text.getOrNull(finish + 1) == '$') return null
            var braces = 0
            for (index in body until finish) if (!escaped(text, index)) {
                val c = text[index]
                if (c == '{') braces++ else if (c == '}') braces--
                if (braces == 0 && (c == '`' || text[body].isDigit() && c in "，；。：！？")) return null
            }
        }
        if (finish >= 0) {
            val until = finish + marker.length
            return MathRange(at, until, MathExpression(text.substring(body, finish), text.substring(at, until), false))
        }
        if (!pending || limit != end) return null
        val tail = text.substring(body, end)
        if (!parens && (tail.contains('\n') || tail.firstOrNull()?.isDigit() == true && tail.none { it in "\\^_{}=+*/<>" })) return null
        return MathRange(at, end, MathExpression(tail, text.substring(at, end), false, false))
    }
    fun inlineRanges(text: String, pending: Boolean = false): List<MathRange> = buildList {
        var i = 0
        while (i < text.length) {
            if (text[i] == '`' && !escaped(text, i)) { i = codeEnd(text, i); continue }
            val math = inlineAt(text, i, pending = pending)
            if (math != null) { add(math); i = math.end } else i++
        }
    }
    private fun codeEnd(text: String, at: Int): Int {
        var count = 1
        while (text.getOrNull(at + count) == '`') count++
        val marker = "`".repeat(count)
        val found = text.indexOf(marker, at + count)
        return if (found < 0) text.length else found + count
    }
    private fun fenceEnd(text: String, at: Int): Int? {
        val marker = text[at]
        if (marker != '`' && marker != '~') return null
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        if (at - lineStart > 3 || text.substring(lineStart, at).any { !it.isWhitespace() }) return null
        var count = 1
        while (text.getOrNull(at + count) == marker) count++
        if (count < 3) return null
        var line = text.indexOf('\n', at)
        if (line < 0) return text.length
        line++
        while (line < text.length) {
            val end = text.indexOf('\n', line).let { if (it < 0) text.length else it }
            val value = text.substring(line, end).trim()
            if (value.length >= count && value.all { it == marker }) return minOf(end + 1, text.length)
            line = end + 1
        }
        return text.length
    }
    fun displays(text: String): List<MathRange> = buildList {
        var i = 0
        while (i < text.length) {
            val fenceEnd = fenceEnd(text, i)
            if (fenceEnd != null) { i = fenceEnd; continue }
            if (text[i] == '`' && !escaped(text, i)) { i = codeEnd(text, i); continue }
            val inline = inlineAt(text, i)
            if (inline != null) { i = inline.end; continue }
            if (escaped(text, i)) { i++; continue }
            val marker = when { text.startsWith("$$", i) -> "$$"; text.startsWith("\\[", i) -> "\\["; else -> "" }
            if (marker.isNotEmpty()) {
                val ending = if (marker == "$$") "$$" else "\\]"
                val body = i + marker.length
                val finish = close(text, ending, body, text.length)
                val until = if (finish < 0) text.length else finish + ending.length
                add(MathRange(i, until, MathExpression(text.substring(body, if (finish < 0) text.length else finish).trim(), text.substring(i, until), true, finish >= 0)))
                i = until; continue
            }
            if (text.startsWith("\\begin{", i)) {
                val lineStart = text.lastIndexOf('\n', i - 1) + 1
                val env = begin.find(text, i)?.takeIf { it.range.first == i }?.groupValues?.get(1)
                if (env != null && env in environments && text.substring(lineStart, i).isBlank()) {
                    val opening = "\\begin{$env}"; val ending = "\\end{$env}"
                    var depth = 1; var at = i + opening.length
                    while (depth > 0 && at < text.length) {
                        val nextOpen = text.indexOf(opening, at)
                        val nextClose = text.indexOf(ending, at)
                        if (nextClose < 0) { at = text.length; break }
                        if (nextOpen in at until nextClose) { depth++; at = nextOpen + opening.length }
                        else { depth--; at = nextClose + ending.length }
                    }
                    add(MathRange(i, at, MathExpression(text.substring(i, at), text.substring(i, at), true, depth == 0)))
                    i = at; continue
                }
            }
            i++
        }
    }
    fun chunkEnd(start: Int, proposed: Int, ranges: List<MathRange>): Int {
        val crossing = ranges.firstOrNull { it.start < proposed && it.end > proposed } ?: return proposed
        return if (crossing.start > start) crossing.start else crossing.end
    }
    fun renderable(latex: String): Boolean {
        if (latex.isBlank() || latex.length > MaxLength || prohibited.containsMatchIn(latex)) return false
        var depth = 0
        latex.forEachIndexed { i, c -> if (!escaped(latex, i)) {
            if (c == '{' && ++depth > 96) return false
            if (c == '}' && --depth < 0) return false
        } }
        return depth == 0
    }
}
