package app.chocola.ui

internal enum class MarkdownType { TEXT, HEADING, CODE, MATH, BULLET, NUMBER, QUOTE, RULE, TABLE }
internal data class MarkdownTable(val rows: List<List<String>>, val alignments: List<String>)
internal data class MarkdownBlock(val type: MarkdownType, val text: String, val meta: String = "", val indent: Int = 0, val table: MarkdownTable? = null, val math: MathExpression? = null)
internal data class MarkdownSpan(val block: MarkdownBlock, val start: Int, val end: Int)

private val fence = Regex("^\\s{0,3}(`{3,}|~{3,})(.*)$")
private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.+)$")
private val bullet = Regex("^(\\s*)[-*+]\\s+(.+)$")
private val number = Regex("^(\\s*)(\\d+)[.)]\\s+(.+)$")
private val rule = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
private val tableDivider = Regex(":?-{3,}:?")

internal fun markdownCells(line: String): List<String> {
    val source = line.trim()
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var codeFence = 0
    var index = 0
    while (index < source.length) {
        val math = if (codeFence == 0) MathSyntax.inlineAt(source, index) else null
        if (math != null) { cell.append(math.expression.source); index = math.end; continue }
        val c = source[index]
        when {
            c == '\\' && source.getOrNull(index + 1) == '|' -> { cell.append('|'); index += 2 }
            c == '`' -> {
                var length = 1
                while (source.getOrNull(index + length) == '`') length++
                if (codeFence == 0) codeFence = length else if (codeFence == length) codeFence = 0
                repeat(length) { cell.append('`') }; index += length
            }
            c == '|' && codeFence == 0 -> { cells += cell.toString().trim(); cell.clear(); index++ }
            else -> { cell.append(c); index++ }
        }
    }
    cells += cell.toString().trim()
    if (source.startsWith('|')) cells.removeAt(0)
    if (source.endsWith('|') && !source.endsWith("\\|")) cells.removeAt(cells.lastIndex)
    return cells
}
private fun divider(line: String): List<String>? = markdownCells(line).takeIf { it.isNotEmpty() && it.all(tableDivider::matches) }
private fun special(line: String): Boolean = fence.matches(line) || heading.matches(line) || bullet.matches(line) || number.matches(line) || rule.matches(line) || line.trimStart().startsWith('>')

internal fun markdownBlocks(text: String): List<MarkdownBlock> = markdownSpans(text).map { it.block }

internal fun markdownSpans(text: String): List<MarkdownSpan> {
    val formulas = MathSyntax.displays(text)
    if (formulas.isEmpty()) return plainMarkdownSpans(text)
    return buildList {
        var start = 0
        for (formula in formulas) {
            if (formula.start > start) addAll(plainMarkdownSpans(text.substring(start, formula.start)).map { it.copy(start = it.start + start, end = it.end + start) })
            add(MarkdownSpan(MarkdownBlock(MarkdownType.MATH, formula.expression.latex, math = formula.expression), formula.start, formula.end))
            start = formula.end
        }
        if (start < text.length) addAll(plainMarkdownSpans(text.substring(start)).map { it.copy(start = it.start + start, end = it.end + start) })
    }
}

private fun plainMarkdownSpans(text: String): List<MarkdownSpan> {
    val lines = text.lines()
    val inlineMath = MathSyntax.inlineRanges(text, pending = true)
    val out = mutableListOf<MarkdownBlock>()
    val spans = mutableListOf<MarkdownSpan>()
    val offsets = IntArray(lines.size + 1)
    lines.forEachIndexed { index, line -> offsets[index + 1] = offsets[index] + line.length + 1 }
    fun insideMath(line: Int) = inlineMath.any { it.start < offsets[line] && it.end > offsets[line] }
    var i = 0
    while (i < lines.size) {
        val firstLine = i
        val countBefore = out.size
        val line = lines[i]
        val code = fence.matchEntire(line)
        val title = heading.matchEntire(line)
        val unordered = bullet.matchEntire(line)
        val ordered = number.matchEntire(line)
        val columns = if (line.contains('|') && i + 1 < lines.size) divider(lines[i + 1])?.takeIf { it.size == markdownCells(line).size && it.isNotEmpty() } else null
        when {
            code != null -> {
                val marker = code.groupValues[1]
                val body = mutableListOf<String>(); i++
                while (i < lines.size) {
                    val candidate = lines[i].trim()
                    if (candidate.takeWhile { it == marker.first() }.length >= marker.length && candidate.all { it == marker.first() }) break
                    body += lines[i]; i++
                }
                val content = body.joinToString("\n")
                out += if (code.groupValues[2].trim().equals("math", true)) MarkdownBlock(MarkdownType.MATH, content,
                    math = MathExpression(content, text.substring(offsets[firstLine], offsets[minOf(i + 1, lines.size)].coerceAtMost(text.length)), true, i < lines.size))
                    else MarkdownBlock(MarkdownType.CODE, content, code.groupValues[2].trim())
                if (i < lines.size) i++
            }
            line.isBlank() -> i++
            title != null -> { out += MarkdownBlock(MarkdownType.HEADING, title.groupValues[2].trimEnd().replace(Regex("\\s+#+$"), ""), title.groupValues[1].length.toString()); i++ }
            rule.matches(line) -> { out += MarkdownBlock(MarkdownType.RULE, ""); i++ }
            columns != null -> {
                val header = markdownCells(line)
                val count = header.size
                val rows = mutableListOf(header)
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    val values = markdownCells(lines[i]); rows += List(count) { values.getOrElse(it) { "" } }; i++
                }
                val alignments = List(count) { index -> columns.getOrElse(index) { "---" }.let { if (it.startsWith(':') && it.endsWith(':')) "center" else if (it.endsWith(':')) "end" else "start" } }
                out += MarkdownBlock(MarkdownType.TABLE, "", table = MarkdownTable(rows, alignments))
            }
            unordered != null || ordered != null -> {
                val match = unordered ?: requireNotNull(ordered)
                val indentation = match.groupValues[1].replace("\t", "    ").length / 2
                val content = StringBuilder(match.groupValues[if (ordered != null) 3 else 2]); i++
                while (i < lines.size && lines[i].isNotBlank() && lines[i].first().isWhitespace() && !special(lines[i])) {
                    content.append('\n').append(lines[i].trimStart()); i++
                }
                out += MarkdownBlock(if (ordered != null) MarkdownType.NUMBER else MarkdownType.BULLET, content.toString(), ordered?.groupValues?.get(2).orEmpty(), indentation.coerceAtMost(6))
            }
            line.trimStart().startsWith('>') -> {
                val content = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith('>')) { content += lines[i].trimStart().removePrefix(">").trimStart(); i++ }
                out += MarkdownBlock(MarkdownType.QUOTE, content.joinToString("\n"))
            }
            else -> {
                val content = mutableListOf(line); i++
                while (i < lines.size && (insideMath(i) || lines[i].isNotBlank() && !special(lines[i]) && !(lines[i].contains('|') && i + 1 < lines.size && divider(lines[i + 1]) != null))) { content += lines[i]; i++ }
                out += MarkdownBlock(MarkdownType.TEXT, content.joinToString("\n"))
            }
        }
        if (out.size > countBefore) spans += MarkdownSpan(out.last(), offsets[firstLine], offsets[i].coerceAtMost(text.length))
    }
    return spans
}

/** Completed prefix blocks never reparse while the last two blocks accept streamed input. */
internal class StreamingMarkdown {
    private var previous = ""
    private var through = 0
    private val committed = mutableListOf<MarkdownBlock>()
    fun update(text: String, streaming: Boolean): List<MarkdownBlock> {
        if (!streaming) { previous = ""; through = 0; committed.clear(); return MarkdownDocuments.parse(text) }
        if (!text.startsWith(previous)) { committed.clear(); through = 0 }
        previous = text
        val tail = text.substring(through)
        val parsed = markdownSpans(tail)
        val stable = parsed.dropLast(2)
        val result = committed.toList() + parsed.map { it.block }.let { blocks ->
            val last = blocks.lastOrNull()
            val pending = last?.takeIf { it.type == MarkdownType.TEXT && it.text.trim().matches(Regex("(?:#{1,6}|[-+*>]|[0-9]{1,3}[.)]|`{1,2})\\s*")) }
            if (pending != null) blocks.dropLast(1) else blocks
        }
        if (stable.isNotEmpty()) {
            committed += stable.map { it.block }
            through += stable.last().end
        }
        return result
    }
}
