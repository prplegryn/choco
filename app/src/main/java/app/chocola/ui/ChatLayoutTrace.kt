package app.chocola.ui

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListState
import app.chocola.data.Diagnostics

/** Primitive counters between samples; reading these must not subscribe the measure to scrolling. */
internal class ChatLayoutTrace(private val logs: Diagnostics, private val session: String) {
    private var height = -1
    private var footer = -1
    private var ime = -1
    private var last = 0L
    private var slow = 0L
    fun measured(list: LazyListState, started: Long, window: Int, header: Int, composer: Int, imeBottom: Int, anchored: Boolean,
        following: Boolean, reading: Boolean, ready: Boolean) {
        val nanos = System.nanoTime() - started
        logs.timing("ui.chat.measure", nanos)
        val now = SystemClock.elapsedRealtime()
        val changed = window != height || composer != footer || imeBottom != ime
        val first = height == -1
        height = window; footer = composer; ime = imeBottom
        val tooSlow = nanos > 8_000_000 && now - slow > 1000
        if (!first && !tooSlow && !(changed && now - last >= 64)) return
        if (tooSlow) slow = now
        last = now
        val info = list.layoutInfo
        val end = info.visibleItemsInfo.lastOrNull()
        logs.event("ui.chat.layout", mapOf("session" to session, "first" to first, "ready" to ready,
            "measureMs" to nanos / 1_000_000.0, "windowPx" to window, "headerPx" to header, "footerPx" to composer,
            "imeBottomPx" to imeBottom, "anchorRequested" to anchored, "following" to following, "readingTools" to reading,
            "scrolling" to list.isScrollInProgress, "firstIndex" to list.firstVisibleItemIndex, "firstOffsetPx" to list.firstVisibleItemScrollOffset,
            "lastIndex" to end?.index, "lastBottomPx" to end?.let { it.offset + it.size }, "viewportEndPx" to info.viewportEndOffset,
            "bottomPaddingPx" to info.afterContentPadding, "items" to info.totalItemsCount, "motions" to logs.motionLabel))
    }
}
