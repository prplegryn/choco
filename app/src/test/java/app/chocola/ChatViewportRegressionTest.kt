package app.chocola

import app.chocola.data.*
import app.chocola.ui.*
import org.junit.Assert.*
import org.junit.Test

class ChatViewportRegressionTest {
    @Test fun entryAndImeFramesAnchorImmediatelyButStreamingDoesNotRepeatedlySnap() {
        val layout = ChatViewportLayout()
        assertTrue(layout.anchor(0, 1000, 180, following = true))
        repeat(10) { assertFalse(layout.anchor(0, 1000, 180, following = true)) }
        for (footer in listOf(210, 260, 330, 410, 470)) assertTrue(layout.anchor(0, 1000, footer, following = true))
        assertFalse(layout.anchor(0, 1000, 470, following = true))
        for (footer in listOf(410, 330, 260, 210, 180)) assertTrue(layout.anchor(0, 1000, footer, following = true))
        assertTrue(layout.anchor(1, 1000, 180, following = true))
    }
    @Test fun returningWhileReadingAndOpeningTheKeyboardCannotPullHistoryToTheBottom() {
        val layout = ChatViewportLayout()
        assertFalse(layout.anchor(0, 1000, 180, following = false))
        assertFalse(layout.anchor(1, 1000, 180, following = false))
        assertFalse(layout.anchor(1, 1000, 470, following = false))
        assertFalse(layout.anchor(1, 800, 470, following = false))
    }
    @Test fun navigationReusesThePreparedMathAndStableRowsOfTheOriginalSession() {
        val cache = ChatTimelineCache()
        val first = TimelineInput("first", listOf(Message(sessionId = "first", role = "assistant", text = "结论\n\n公式 $" + "x^2$")), emptyList(), emptyList(), emptySet(), false)
        val prepared = cache.build(first)
        cache.build(first.copy(session = "second", messages = listOf(Message(sessionId = "second", role = "user", text = "另一会话"))))
        assertSame(prepared, cache.peek(first))
        assertSame(prepared, cache.build(first))
        assertEquals(prepared.items.map { it.key }, cache.build(first).items.map { it.key })
        assertNull(cache.peek(first.copy(messages = first.messages + Message(sessionId = "first", role = "user", text = "更新"))))
    }
}
