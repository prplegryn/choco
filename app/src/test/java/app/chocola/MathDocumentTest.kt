package app.chocola

import app.chocola.data.Message
import app.chocola.ui.*
import org.junit.Assert.*
import org.junit.Test

class MathDocumentTest {
    @Test fun inlineFormulasKeepExactSourceAndDoNotInterpretTexAsEmphasis() {
        val input = "能量 \\(E=mc^2\\)，以及 \$\\frac{x_i}{y_j}\$。"
        val rich = InlineDocument.parse(input, false)
        val expressions = MathTypesetter.expressions(rich, false).values.toList()
        assertEquals(listOf("E=mc^2", "\\frac{x_i}{y_j}"), expressions.map { it.latex })
        assertEquals(input, rich.text)
        assertTrue(rich.spanStyles.isEmpty())
    }
    @Test fun codeAndPricesDoNotTurnIntoMath() {
        val input = "预算 \$5，另加 \$10，\\\$20；`\\(x\\)` 与 `\$a_b\$` 是源码。"
        val rich = InlineDocument.parse(input, false)
        assertTrue(MathTypesetter.expressions(rich, false).isEmpty())
        assertTrue(rich.text.contains("\$5，另加 \$10，\$20"))
        assertTrue(rich.text.contains("\$a_b\$"))
    }
    @Test fun dollarsInsideInlineMathAndEscapedDelimitersStayInTheFormula() {
        val source = "\\(\\text{cost }\\\$5 + x\\)"
        assertEquals("\\text{cost }\\\$5 + x", MathSyntax.inlineAt(source, 0)!!.expression.latex)
        assertNull(MathSyntax.inlineAt("\\\$x\$", 1))
        assertNull(MathSyntax.inlineAt("\$ x \$", 0))
    }
    @Test fun aPriceBeforeARealFormulaDoesNotCaptureThatFormulasOpeningDollar() {
        listOf("预算 \$5，另加 \$10；求 \$x^2\$。", "预算\$5，另加\$10；求\$x^2\$。", "\$5 and \$10; then \$x^2\$").forEach { source ->
            val expressions = MathTypesetter.expressions(InlineDocument.parse(source, false), false).values
            assertEquals(listOf("x^2"), expressions.map { it.latex })
        }
        assertEquals("2\\text{，条件成立}", MathSyntax.inlineAt("\$2\\text{，条件成立}\$", 0)!!.expression.latex)
    }
    @Test fun displayFormulasCanStartBesideProseAndPreserveMultilineAlignment() {
        val formula = "\\begin{aligned}\na&=b+c\\\\\n &=d\n\\end{aligned}"
        val blocks = markdownBlocks("前文\\[$formula\\]后文\n\n\$\$x^2\$\$")
        assertEquals(listOf(MarkdownType.TEXT, MarkdownType.MATH, MarkdownType.TEXT, MarkdownType.MATH), blocks.map { it.type })
        assertEquals(formula, blocks[1].text)
        assertEquals("\\[$formula\\]", blocks[1].math!!.source)
        assertTrue(blocks[1].math!!.complete)
    }
    @Test fun mathFencesRenderButLatexCodeFencesRemainSource() {
        val blocks = markdownBlocks("```math\n\\int_0^1 x\\,dx\n```\n\n```latex\n\$\$x\$\$\n```\n\n~~~text\n\\[y\\]\n~~~")
        assertEquals(listOf(MarkdownType.MATH, MarkdownType.CODE, MarkdownType.CODE), blocks.map { it.type })
        assertEquals("\\int_0^1 x\\,dx", blocks[0].math!!.latex)
        assertTrue(blocks[0].math!!.complete)
    }
    @Test fun bareEquationAndNestedMatrixEnvironmentsAreSingleBlocks() {
        val source = "\\begin{align*}A&=\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}\\\\x&=0\\end{align*}"
        val block = markdownBlocks(source).single()
        assertEquals(MarkdownType.MATH, block.type)
        assertEquals(source, block.math!!.source)
        assertTrue(block.math!!.complete)
    }
    @Test fun unclosedMathStaysPendingWhileStreamingAndRetainsSourceAtTheEnd() {
        val stream = StreamingMarkdown()
        val first = stream.update("说明\n\n\\[\\frac{a}{", true)
        assertFalse(first.last().math!!.complete)
        val next = stream.update("说明\n\n\\[\\frac{a}{b}\\]\n\n结论", true)
        assertTrue(next[1].math!!.complete)
        assertEquals(listOf("说明", "\\frac{a}{b}", "结论"), next.map { it.text })
        val unfinished = stream.update("\\[x+", false).single().math!!
        assertFalse(unfinished.complete)
        assertEquals("\\[x+", unfinished.source)
        val inline = InlineDocument.parse("前面 \\(\\frac{x}{", true)
        assertFalse(MathTypesetter.expressions(inline, true).values.single().complete)
        assertTrue(MathTypesetter.expressions(InlineDocument.parse("前面 \\(\\frac{x}{", false), false).isEmpty())
        assertEquals("前面 \\(\\frac{x}{", InlineDocument.parse("前面 \\(\\frac{x}{", false).text)
    }
    @Test fun proseBreaksDoNotTouchTexRowsOrRemoveSpacesBesideMath() {
        val input = "前段\n文字 \\(\\begin{matrix}a&b\\\\\nc&d\\end{matrix}\\) and\nnext"
        val expected = "前段文字 \\(\\begin{matrix}a&b\\\\\nc&d\\end{matrix}\\) and next"
        assertEquals(expected, ProseLayout.softBreaks(input))
    }
    @Test fun mathPipesDoNotSplitTableCells() {
        val line = "| 范数 | \$|x|+\\lVert y\\rVert\$ |"
        assertEquals(listOf("范数", "\$|x|+\\lVert y\\rVert\$"), markdownCells(line))
        val table = markdownBlocks("| 名称 | 表达式 |\n| --- | --- |\n$line").single()
        assertEquals(2, table.table!!.rows.last().size)
        assertEquals(1, MathTypesetter.expressions(RichDocuments.inline(table.table!!.rows.last()[1], false), false).size)
    }
    @Test fun multilineInlineMathCannotBeBrokenByBlankLinesOrMarkdownMarkers() {
        val source = "推导 \\(\\begin{aligned}a&=b\\\\\n\n x_1&=y_1\\end{aligned}\\) 完成"
        val block = markdownBlocks(source).single()
        val rich = RichDocuments.inline(block.text, false)
        assertEquals("\\begin{aligned}a&=b\\\\\n\n x_1&=y_1\\end{aligned}", MathTypesetter.expressions(rich, false).values.single().latex)
    }
    @Test fun longConversationChunksNeverSplitAnInlineFormula() {
        val formula = "\\(" + "x+".repeat(1400) + "y\\)"
        val text = "a".repeat(2000) + formula + " suffix"
        val message = Message(sessionId = "s", role = "assistant", text = text)
        val parts = TimelineProjector().build(listOf(message), emptySet(), emptySet()).filterIsInstance<TextPiece>()
        assertEquals(text, parts.joinToString("") { it.block.text })
        assertEquals(1, parts.count { MathTypesetter.expressions(it.prepared.content, false).isNotEmpty() })
        assertEquals(1, MathSyntax.inlineRanges(parts.first { formula in it.block.text }.block.text).size)
    }
    @Test fun formulaSourceOffsetsAndStreamingPrefixesDoNotLoseText() {
        val source = "First\n\n\$\$a+b\$\$\n\nSecond\n\n\\[c+d\\]\n\nLast"
        val spans = markdownSpans(source)
        assertEquals(source.length, spans.last().end)
        assertTrue(spans.zipWithNext().all { (one, two) -> one.end <= two.start })
        val stream = StreamingMarkdown()
        val first = stream.update(source, true)
        val next = stream.update(source + " grows", true)
        assertSame(first.first(), next.first())
        assertEquals(listOf("First", "a+b", "Second", "c+d", "Last grows"), next.map { it.text })
    }
    @Test fun malformedOrResourceExhaustingMathKeepsTheOriginalSource() {
        assertFalse(MathSyntax.renderable("\\frac{a}{"))
        assertFalse(MathSyntax.renderable("{".repeat(100) + "x" + "}".repeat(100)))
        assertFalse(MathSyntax.renderable("x".repeat(MathSyntax.MaxLength + 1)))
        assertFalse(MathSyntax.renderable("\\includegraphics{/private/image}"))
        assertFalse(MathSyntax.renderable("\\newcommand{\\x}{\\x}\\x"))
        assertTrue(MathSyntax.renderable("\\{x\\}+\\frac{a}{b}"))
    }
    @Test fun documentEnvironmentsLabelsAndEquationTagsNormalizeWithoutChangingCopySource() {
        val source = "\\begin{equation}x=y\\label{eq:x}\\tag{2}\\end{equation}"
        val expression = markdownBlocks(source).single().math!!
        assertEquals(source, expression.source)
        assertEquals("x=y\\qquad\\text{(2)}", MathNormalizer.normalize(source))
        assertEquals("\\begin{aligned}a&=b\\\\c&=d\\end{aligned}", MathNormalizer.normalize("\\begin{align*}a&=b\\\\c&=d\\end{align*}"))
    }
    @Test fun inlineMetricsAlignBaselinesWithoutClippingFractionsOrDescenders() {
        listOf(Triple(16f, 13f, -5f), Triple(48f, 31f, -6f), Triple(80f, 60f, -5f)).forEach { (height, baseline, center) ->
            val box = inlineMathBox(35f, height, baseline, center)
            assertTrue(box.top >= 0f)
            assertTrue(box.top + height <= box.height + .001f)
            assertEquals(0f, center - box.height / 2f + box.top + baseline, .001f)
        }
    }
}
