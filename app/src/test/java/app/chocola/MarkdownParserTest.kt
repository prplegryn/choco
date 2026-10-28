package app.chocola

import app.chocola.ui.MarkdownType
import app.chocola.ui.markdownBlocks
import app.chocola.ui.markdownCells
import org.junit.Assert.*
import org.junit.Test

class MarkdownParserTest {
    @Test fun unclosedStreamingCodeRetainsEveryLine() {
        val block = markdownBlocks("```python\nprint('你好')\n\n").single()
        assertEquals(MarkdownType.CODE, block.type)
        assertEquals("python", block.meta)
        assertEquals("print('你好')\n\n", block.text)
    }
    @Test fun shorterFenceInsideCodeDoesNotCloseTheBlock() {
        val parsed = markdownBlocks("````sh\nprintf ok\n```\n````\nAfter")
        assertEquals("printf ok\n```", parsed[0].text)
        assertEquals(MarkdownType.CODE, parsed[0].type)
        assertEquals("After", parsed[1].text)
    }
    @Test fun tablePipesInsideCodeAndEscapedPipesStayInTheirCells() {
        assertEquals(listOf("`a|b`", "c|d"), markdownCells("| `a|b` | c\\|d |"))
        val parsed = markdownBlocks("| 名称 | 数值 |\n| :--- | ---: |\n| `a|b` | 42 |")
        val table = requireNotNull(parsed.single().table)
        assertEquals(listOf("start", "end"), table.alignments)
        assertEquals(listOf("`a|b`", "42"), table.rows[1])
    }
    @Test fun numberedListsKeepTheirNumbersNestingAndContinuation() {
        val parsed = markdownBlocks("3. first\n   continued\n   - nested\n4. second")
        assertEquals(listOf(MarkdownType.NUMBER, MarkdownType.BULLET, MarkdownType.NUMBER), parsed.map { it.type })
        assertEquals("3", parsed[0].meta)
        assertEquals("first\ncontinued", parsed[0].text)
        assertEquals(1, parsed[1].indent)
        assertEquals("4", parsed[2].meta)
    }
    @Test fun multilineQuotesShareOneBlockAndRulesEndParagraphs() {
        val parsed = markdownBlocks("> first\n> second\n\nText\n---\n# Heading")
        assertEquals("first\nsecond", parsed[0].text)
        assertEquals(listOf(MarkdownType.QUOTE, MarkdownType.TEXT, MarkdownType.RULE, MarkdownType.HEADING), parsed.map { it.type })
    }
    @Test fun invalidTableDividerCannotSilentlyDropParagraphText() {
        val parsed = markdownBlocks("a | b | c\n--- | ---\nremaining")
        assertTrue(parsed.none { it.type == MarkdownType.TABLE })
        assertTrue(parsed.joinToString { it.text }.contains("a | b | c"))
        assertTrue(parsed.joinToString { it.text }.contains("remaining"))
    }
}
