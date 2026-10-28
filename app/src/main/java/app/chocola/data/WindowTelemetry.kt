package app.chocola.data

import android.os.Build
import android.os.SystemClock
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import androidx.annotation.RequiresApi
import java.util.IdentityHashMap

/** Observes the decor's animation; Compose keeps ownership of its child inset handling. */
class WindowTelemetry(private val window: Window, private val logs: Diagnostics) : AutoCloseable {
    private var callback: InsetsTrace? = null
    fun attach() {
        if (Build.VERSION.SDK_INT >= 30) callback = InsetsTrace().also { window.decorView.setWindowInsetsAnimationCallback(it) }
    }
    override fun close() {
        if (Build.VERSION.SDK_INT >= 30) { callback?.close(); callback = null; window.decorView.setWindowInsetsAnimationCallback(null) }
    }
    @RequiresApi(30)
    private inner class InsetsTrace : WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        private inner class State(val span: TraceSpan) {
            var frames = 0; var lastFrame = 0L; var maxGap = 0L; var lastSample = 0L; var bottom = 0; var fraction = 0f
        }
        private val active = IdentityHashMap<WindowInsetsAnimation, State>()
        override fun onPrepare(animation: WindowInsetsAnimation) {
            val category = if (animation.typeMask and WindowInsets.Type.ime() != 0) "ime" else "system_bars"
            active[animation] = State(logs.begin("motion", fields = mapOf("category" to category, "typeMask" to animation.typeMask,
                "configuredDurationMs" to animation.durationMillis, "interpolator" to animation.interpolator?.javaClass?.simpleName)))
        }
        override fun onStart(animation: WindowInsetsAnimation, bounds: WindowInsetsAnimation.Bounds): WindowInsetsAnimation.Bounds {
            active[animation]?.span?.stage("running", mapOf("lowerBottomPx" to bounds.lowerBound.bottom, "upperBottomPx" to bounds.upperBound.bottom,
                "windowWidth" to window.decorView.width, "windowHeight" to window.decorView.height), record = true)
            return bounds
        }
        override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
            val now = SystemClock.uptimeMillis()
            runningAnimations.forEach { animation -> active[animation]?.let { state ->
                if (state.lastFrame > 0) state.maxGap = maxOf(state.maxGap, now - state.lastFrame)
                state.lastFrame = now; state.frames++; state.bottom = insets.getInsets(WindowInsets.Type.ime()).bottom; state.fraction = animation.interpolatedFraction
                if (now - state.lastSample >= 64) {
                    state.lastSample = now
                    state.span.event("ui.insets.progress", mapOf("fraction" to state.fraction, "imeBottomPx" to state.bottom,
                        "navigationBottomPx" to insets.getInsets(WindowInsets.Type.navigationBars()).bottom, "imeVisible" to insets.isVisible(WindowInsets.Type.ime())))
                }
            } }
            return insets
        }
        override fun onEnd(animation: WindowInsetsAnimation) {
            active.remove(animation)?.let { it.span.finish("done", mapOf("frames" to it.frames, "maxFrameGapMs" to it.maxGap, "finalImeBottomPx" to it.bottom, "finalFraction" to it.fraction)) }
        }
        fun close() { active.values.forEach { it.span.finish("window_destroyed") }; active.clear() }
    }
}
