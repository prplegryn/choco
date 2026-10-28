package app.chocola.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.exp

internal object ChatScrollMath {
    fun distance(lastIndex: Int, lastEnd: Int, total: Int, viewportEnd: Int, after: Int, height: Int): Float {
        if (lastIndex < 0 || total == 0) return 0f
        val visibleGap = (lastEnd + after - viewportEnd).coerceAtLeast(0).toFloat()
        return if (lastIndex == total - 1) visibleGap else maxOf(visibleGap, height * .8f)
    }
    fun advance(distance: Float, seconds: Float): Float = (distance * (1f - exp(-seconds.coerceIn(.001f, .05f) / .085f))).coerceIn(0f, distance.coerceAtLeast(0f))
}

internal object LatestVisibility {
    fun show(distance: Float, viewport: Int, previouslyVisible: Boolean): Boolean = viewport > 0 &&
        distance > viewport * (if (previouslyVisible) 1.5f else 3f)
}

/** Previously visited rows provide real heights; unknown rows use a conservative viewport estimate. */
internal class ChatEndTracker {
    private val heights = LinkedHashMap<String, Int>()
    fun record(key: String, height: Int) {
        if (height <= 0) return
        heights[key] = height
        while (heights.size > 2048) heights.remove(heights.keys.first())
    }
    fun remaining(items: List<ChatItem>, older: Boolean, lastIndex: Int, lastEnd: Int, viewportEnd: Int, padding: Int, viewport: Int, visibleCount: Int): Float {
        var distance = (lastEnd + padding - viewportEnd).coerceAtLeast(0).toFloat()
        val offset = if (older) 1 else 0
        val estimate = viewport / visibleCount.coerceIn(2, 8)
        for (index in lastIndex + 1 until items.size + offset) {
            val key = items.getOrNull(index - offset)?.key ?: "older"
            distance += heights[key] ?: estimate
            if (distance > viewport * 3.2f) break
        }
        return distance
    }
}

/** One continuous follower changes its destination without restarting an animation per token. */
@Composable internal fun FollowChatEnd(list: LazyListState, enabled: Boolean, shouldFollow: () -> Boolean) {
    val following by rememberUpdatedState(shouldFollow)
    LaunchedEffect(list, enabled) {
        if (!enabled) return@LaunchedEffect
        val scale = currentCoroutineContext()[MotionDurationScale]
        coroutineScope {
            val wake = Channel<Unit>(Channel.CONFLATED)
            val observer = launch { snapshotFlow { list.layoutInfo }.collect { wake.trySend(Unit) } }
            try {
                // A geometry request can cancel one scroll without killing the follower.
                for (ignored in wake) {
                    launch {
                        list.scroll(MutatePriority.Default) {
                            var previous = 0L
                            while (following()) {
                                val info = list.layoutInfo
                                val last = info.visibleItemsInfo.lastOrNull() ?: break
                                val gap = ChatScrollMath.distance(last.index, last.offset + last.size, info.totalItemsCount,
                                    info.viewportEndOffset, info.afterContentPadding, info.viewportSize.height)
                                if (gap <= .5f) { if (gap > 0f) scrollBy(gap); break }
                                var consumed = 0f
                                withFrameNanos { now ->
                                    val dt = if (previous == 0L) 1f / 60 else (now - previous) / 1_000_000_000f
                                    previous = now
                                    if (following()) consumed = scrollBy(if (scale?.scaleFactor == 0f) gap else ChatScrollMath.advance(gap, dt / (scale?.scaleFactor ?: 1f)).coerceAtLeast(.5f))
                                }
                                if (consumed < .1f) break
                            }
                        }
                    }.join()
                }
            } finally { observer.cancel(); wake.close() }
        }
    }
}
