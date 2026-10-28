package app.chocola

import app.chocola.ui.*
import app.chocola.data.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import org.junit.Assert.*
import org.junit.Test

class TextPresentationTest {
    @Test fun oldSpinnerPreferencesMigrateToTheWholeCapsuleEffect() {
        listOf("glow", "ring", "orbit", "wave", "dots", "unknown").forEach {
            assertEquals("sheen", Preferences.from(org.json.JSONObject().put("statusStyle", it)).statusStyle)
        }
        listOf("sheen", "ripple", "breath", "mist", "tide", "still").forEach {
            assertEquals(it, Preferences.from(Preferences(statusStyle = it).json()).statusStyle)
        }
    }
    @Test fun anotherChunkDoesNotRestartAnEarlierReveal() {
        val state = TextReveal("")
        state.append("第一段", true, 0)
        val first = state.batches.single()
        val before = TextReveal.opacity(first, 80, 180)
        state.append("第一段继续", true, 80)
        assertEquals(first, state.batches.first())
        assertEquals(before, TextReveal.opacity(state.batches.first(), 80, 180), 0f)
        assertTrue(TextReveal.opacity(first, 120, 180) > before)
        state.advance(181, 180)
        assertEquals(1, state.batches.size)
        assertEquals(80L, state.batches.single().born)
    }
    @Test fun replacementTextSettlesImmediatelyAndReducedMotionHasNoPendingBatches() {
        val state = TextReveal("old")
        state.append("old tail", true, 0)
        state.append("revised", true, 10)
        assertFalse(state.active())
        state.append("revised more", true, 20)
        state.advance(20, 0)
        assertFalse(state.active())
    }
    @Test fun revealMemoryIsBoundedAndSplitSurrogatesDoNotCreateOverlappingMasks() {
        val state = TextReveal("")
        (1..100).forEach { state.append("x".repeat(it), true, it.toLong()) }
        assertTrue(state.batches.size <= 6)
        val emoji = TextReveal("")
        emoji.append("\uD83D", true, 0); emoji.append("\uD83D\uDC08", true, 1)
        assertEquals(1, emoji.batches.size)
        assertEquals(0, emoji.batches.single().start); assertEquals(2, emoji.batches.single().end)
    }
    @Test fun completedStreamingPrefixesAreReusedWithoutDroppingOrDuplicatingBlocks() {
        val parser = StreamingMarkdown()
        val first = parser.update("first\n\nsecond\n\nthird", true)
        val next = parser.update("first\n\nsecond\n\nthird grows\n\nfourth", true)
        assertSame(first.first(), next.first())
        assertEquals(listOf("first", "second", "third grows", "fourth"), next.map { it.text })
        assertEquals("new text", parser.update("new text", true).single().text)
    }
    @Test fun openInlineMarkupAndLinksDoNotFlashDelimitersOrPartialUrls() {
        assertEquals("负载正常", InlineDocument.parse("**负载正常", true).text)
        assertEquals("负载正常", InlineDocument.parse("**负载正常**", false).text)
        assertEquals("查看资料", InlineDocument.parse("查看[资料](https://exam", true).text)
        assertEquals("查看资料", InlineDocument.parse("查看[资料](https://example.com)", false).text)
        assertEquals("文字", InlineDocument.parse("文字**", true).text)
        val formatted = InlineDocument.parse("**普通 *强调* `code`**", false)
        assertEquals("普通 强调 code", formatted.text)
        assertTrue(formatted.spanStyles.any { it.item.fontWeight == FontWeight.Medium })
        assertTrue(formatted.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertEquals("*literal* file_name", InlineDocument.parse("\\*literal\\* file_name", false).text)
    }
    @Test fun aPendingBlockPrefixCannotTurnIntoAVisibleTemporaryParagraph() {
        val parser = StreamingMarkdown()
        assertTrue(parser.update("# ", true).isEmpty())
        assertEquals(MarkdownType.HEADING, parser.update("# 网络负载", true).single().type)
    }
    @Test fun followerOnlyAdvancesTowardTheBottomAndHasEquivalentTimingAcrossFrameRates() {
        fun settle(hz: Int): Float {
            var remaining = 1000f
            repeat(hz) { remaining -= ChatScrollMath.advance(remaining, 1f / hz) }
            return remaining
        }
        assertEquals(settle(60), settle(120), .02f)
        listOf(1f, 25f, 800f).forEach { gap -> assertTrue(ChatScrollMath.advance(gap, .016f) in 0f..gap) }
        assertEquals(200f, ChatScrollMath.distance(9, 700, 10, 700, 200, 700), 0f)
        assertEquals(0f, ChatScrollMath.distance(9, 400, 10, 700, 200, 700), 0f)
    }
    @Test fun overviewRetainsSeparateCompleteCommandAndResultWithIndependentParameters() {
        val command = "printf 'hello'\n" + "echo x\n".repeat(100)
        val result = "line\n".repeat(1000)
        val call = ToolCall("c", "run_shell", org.json.JSONObject().put("command", command).put("description", "测量负载").toString())
        val message = Message(sessionId = "s", role = "tool", text = org.json.JSONObject().put("output", result).toString())
        val doc = ToolDocument.prepare(message, call)
        assertEquals(command, doc.command.text); assertEquals(result, doc.result.text)
        assertTrue(doc.result.chunks.size > 20)
        assertEquals("测量负载", org.json.JSONObject(doc.parameters.text).getString("description"))
        val longLine = "🐈".repeat(3000)
        assertEquals(longLine, displayLines(longLine).joinToString(""))
        assertTrue(displayLines(longLine).none { it.last().isHighSurrogate() })
    }
}
