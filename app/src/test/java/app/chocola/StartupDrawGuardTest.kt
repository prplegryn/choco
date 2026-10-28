package app.chocola

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Callback and scheduling checks only. No Activity, emulator, screenshots or UI inspection. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class StartupDrawGuardTest {
    private fun view() = View(RuntimeEnvironment.getApplication())
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun aMissingLayoutCallbackCannotKeepTheWindowBlankForever() {
        val view = view()
        var timeouts = 0
        val draws = mutableListOf<String>()
        StartupDrawGuard(view, { false }, { timeouts++ }, { _, reason -> draws += reason })
        assertTrue("Initial incomplete frame is briefly held", view.viewTreeObserver.dispatchOnPreDraw())
        idle(1199)
        assertTrue(view.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(0, timeouts)
        idle(1)
        assertEquals(1, timeouts)
        assertFalse("Deadline must allow progress even without content", view.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(listOf("deadline"), draws)
        idle(5000)
        assertFalse(view.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(1, timeouts)
        assertEquals(1, draws.size)
    }

    @Test fun preparedContentReleasesImmediatelyAndCancelsTheDeadline() {
        val view = view()
        var ready = false
        var timeouts = 0
        val draws = mutableListOf<String>()
        StartupDrawGuard(view, { ready }, { timeouts++ }, { _, reason -> draws += reason })
        assertTrue(view.viewTreeObserver.dispatchOnPreDraw())
        ready = true
        assertFalse(view.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(listOf("content"), draws)
        idle(5000)
        assertEquals(0, timeouts)
        assertEquals(1, draws.size)
    }

    @Test fun destroyingAWindowCancelsItsListenerAndDeadline() {
        val view = view()
        var callbacks = 0
        val guard = StartupDrawGuard(view, { false }, { callbacks++ }, { _, _ -> callbacks++ })
        assertTrue(view.viewTreeObserver.dispatchOnPreDraw())
        guard.close()
        guard.close()
        idle(5000)
        assertFalse(view.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(0, callbacks)
    }

    @Test fun aRecreatedWindowOwnsAnIndependentStartupDeadline() {
        val view = view()
        var abandoned = 0
        var current = 0
        val old = StartupDrawGuard(view, { false }, { abandoned++ }, { _, _ -> abandoned++ })
        idle(600)
        old.close()
        StartupDrawGuard(view, { false }, {}, { _, _ -> current++ })
        idle(600)
        assertTrue("Old deadline must not release the new window", view.viewTreeObserver.dispatchOnPreDraw())
        idle(600)
        assertFalse(view.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(0, abandoned)
        assertEquals(1, current)
    }

    @Test fun initialScrollCanBeRequestedWithoutWaitingForLazyListLayout() = runTest {
        val list = LazyListState()
        val blocking = launch { list.scrollToItem(80) }
        runCurrent()
        assertFalse("scrollToItem waits for an unmeasured list", blocking.isCompleted)
        blocking.cancel()
        runCurrent()
        list.requestScrollToItem(80)
        assertEquals(80, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
    }
}
