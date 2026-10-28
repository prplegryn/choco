package app.chocola.ui

/** Markdown soft breaks become prose; explicit hard breaks and original source remain intact. */
internal object ProseLayout {
    private val scripts = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
    private fun cjk(c: Char) = Character.UnicodeScript.of(c.code) in scripts || c.code in 0x3000..0x303F || c.code in 0xFF01..0xFF65
    fun softBreaks(text: String): String {
        if ('\n' !in text) return text
        val formulas = MathSyntax.inlineRanges(text, pending = true)
        if (formulas.isEmpty()) return plainBreaks(text)
        var marker = "\u0000math\u0000"
        while (marker in text) marker += "_"
        val protected = buildString {
            var start = 0
            for ((index, formula) in formulas.withIndex()) {
                append(text.substring(start, formula.start))
                append(marker).append(index).append(marker)
                start = formula.end
            }
            append(text.substring(start))
        }
        var result = plainBreaks(protected)
        formulas.forEachIndexed { index, formula -> result = result.replace("$marker$index$marker", formula.expression.source) }
        return result
    }
    private fun plainBreaks(text: String): String {
        val lines = text.split('\n')
        return buildString {
            lines.forEachIndexed { index, raw ->
                val line = raw.trimStart()
                val slashes = line.takeLastWhile { it == '\\' }.length
                val hard = line.endsWith("  ") || slashes % 2 == 1
                val clean = if (slashes % 2 == 1) line.dropLast(1) else line.trimEnd()
                append(clean)
                if (index < lines.lastIndex) {
                    val next = lines[index + 1].trimStart()
                    if (hard || clean.isEmpty() || next.isEmpty()) append('\n')
                    else if (!(cjk(clean.last()) && cjk(next.first()))) append(' ')
                }
            }
        }
    }
}
