package app.chocola.ui

/** Immutable batches retain their start time when another network chunk arrives. */
internal data class RevealBatch(val start: Int, val end: Int, val born: Long)
internal class TextReveal(initial: String) {
    private var text = initial
    private val pending = ArrayDeque<RevealBatch>()
    val batches get() = pending.toList()
    fun append(next: String, animate: Boolean, now: Long): Boolean {
        if (next == text) return false
        if (!next.startsWith(text)) { pending.clear(); text = next; return true }
        val previous = text.length
        text = next
        if (animate && next.length > previous) {
            var start = previous.coerceAtLeast(next.length - 128)
            if (start > 0 && next.getOrNull(start)?.isLowSurrogate() == true) start--
            var born = now
            while (pending.isNotEmpty() && pending.last().end > start) {
                val prior = pending.removeLast(); start = minOf(start, prior.start); born = minOf(born, prior.born)
            }
            pending.addLast(RevealBatch(start, next.length, born))
            while (pending.size > 6) pending.removeFirst()
        }
        return true
    }
    fun advance(now: Long, duration: Long): Boolean {
        var changed = false
        while (pending.isNotEmpty() && (duration <= 0 || now - pending.first().born >= duration)) { pending.removeFirst(); changed = true }
        return changed
    }
    fun active() = pending.isNotEmpty()
    companion object {
        fun opacity(batch: RevealBatch, now: Long, duration: Long): Float {
            if (duration <= 0) return 1f
            val t = ((now - batch.born).toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            return .24f + .76f * eased
        }
    }
}
