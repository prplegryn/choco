package app.chocola.ui

import app.chocola.data.ChatActivity
import app.chocola.data.Message
import app.chocola.data.Diagnostics

internal data class TimelineInput(val session: String, val messages: List<Message>, val groups: List<String>,
    val steps: List<String>, val closing: Set<String>, val running: Boolean, val imageMode: Boolean = false)
internal data class TimelineFrame(val session: String, val ready: Boolean, val items: List<ChatItem>, val activity: ChatActivity = ChatActivity())

/** Keep prepared documents across navigation, without parsing rich text on the main thread. */
internal class ChatTimelineCache(private val logs: Diagnostics? = null) {
    private class Entry(val projector: TimelineProjector = TimelineProjector()) {
        @Volatile var prepared: Pair<TimelineInput, TimelineFrame>? = null
    }
    private val entries = LinkedHashMap<String, Entry>(4, .75f, true)
    fun peek(input: TimelineInput): TimelineFrame? = synchronized(entries) { entries[input.session] }?.prepared?.takeIf { it.first == input }?.second
    fun warm(session: String, messages: List<Message>, running: Boolean) {
        val previous = synchronized(entries) { entries[session] }?.prepared?.first
        build(TimelineInput(session, messages, previous?.groups.orEmpty(), previous?.steps.orEmpty(), emptySet(), running))
    }
    fun build(input: TimelineInput): TimelineFrame {
        val started = System.nanoTime()
        var hit = false
        val entry = synchronized(entries) {
            val value = entries.getOrPut(input.session) { Entry() }
            while (entries.size > 3) entries.remove(entries.keys.first())
            value
        }
        return synchronized(entry) {
            entry.prepared?.takeIf { it.first == input }?.second?.also { hit = true } ?: TimelineFrame(input.session, true,
                entry.projector.build(input.messages, input.groups.toSet(), input.steps.toSet(), input.groups.toSet() + input.closing),
                ChatActivity.project(input.messages, input.running, input.imageMode)).also { entry.prepared = input to it }
        }.also { frame ->
            val elapsed = System.nanoTime() - started
            logs?.timing(if (hit) "timeline.cache_hit" else "timeline.prepare", elapsed)
            if (elapsed > 32_000_000) logs?.event("ui.timeline.slow", mapOf("session" to input.session, "durationMs" to elapsed / 1_000_000.0,
                "cacheHit" to hit, "messages" to input.messages.size, "items" to frame.items.size, "openGroups" to input.groups.size, "openSteps" to input.steps.size))
        }
    }
}

/** Geometry changes already have an animation (IME/window). Apply their anchor in that measure. */
internal class ChatViewportLayout {
    private var epoch = -1
    private var height = -1
    private var footer = -1
    fun anchor(nextEpoch: Int, nextHeight: Int, nextFooter: Int, following: Boolean): Boolean {
        val changed = nextEpoch != epoch || nextHeight != height || nextFooter != footer
        epoch = nextEpoch; height = nextHeight; footer = nextFooter
        return following && changed
    }
    companion object { const val EndOffset = 1_000_000_000 }
}
