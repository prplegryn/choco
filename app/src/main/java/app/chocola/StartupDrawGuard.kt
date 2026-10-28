package app.chocola

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver

/**
 * Hide incomplete startup frames briefly, without preventing Compose's drawing
 * pass indefinitely when a deferred layout needs that pass to make progress.
 * Each Activity owns its guard; destroying it removes both callbacks.
 */
internal class StartupDrawGuard(
    private val view: View,
    private val ready: () -> Boolean,
    private val onTimeout: () -> Unit,
    private val onFirstDraw: (elapsedMs: Long, reason: String) -> Unit,
    private val timeoutMs: Long = 1200L
) : ViewTreeObserver.OnPreDrawListener, AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val started = SystemClock.uptimeMillis()
    private var closed = false
    private var expired = false
    private val deadline = Runnable {
        if (!closed) {
            expire()
            // Readiness is not Compose state; explicitly request the unblocked frame.
            view.postInvalidateOnAnimation()
        }
    }
    init {
        require(timeoutMs > 0)
        view.viewTreeObserver.addOnPreDrawListener(this)
        handler.postDelayed(deadline, timeoutMs)
    }
    private fun expire() {
        if (!expired) {
            expired = true
            if (!ready()) onTimeout()
        }
    }
    override fun onPreDraw(): Boolean {
        if (closed) return true
        val elapsed = SystemClock.uptimeMillis() - started
        if (!ready() && !expired && elapsed < timeoutMs) return false
        if (!ready()) expire()
        val reason = if (ready()) "content" else "deadline"
        close()
        onFirstDraw(elapsed, reason)
        return true
    }
    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(deadline)
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
    }
}
