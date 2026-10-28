package app.chocola

import app.chocola.ui.MarkdownDocuments
import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentsTest {
    @Test fun returningToACompletedReplyReusesItsParsedDocument() {
        val text = "## Cached reply\n\nStable paragraph with **formatting**."
        val first = MarkdownDocuments.parse(text)
        assertSame(first, MarkdownDocuments.parse(text))
        assertSame(first, MarkdownDocuments.peek(text))
    }

    @Test fun oldRepliesAreEvictedInsteadOfGrowingTheCacheForever() {
        val old = "Eviction test oldest reply"
        MarkdownDocuments.parse(old)
        repeat(49) { MarkdownDocuments.parse("Eviction test newer reply $it") }
        assertNull(MarkdownDocuments.peek(old))
        assertNotNull(MarkdownDocuments.peek("Eviction test newer reply 48"))
    }

    @Test fun veryLargeRepliesCanBeReadWithoutBeingRetainedInTheCache() {
        val text = "x".repeat(500_001)
        assertFalse(MarkdownDocuments.parse(text).isEmpty())
        assertNull(MarkdownDocuments.peek(text))
    }
}
