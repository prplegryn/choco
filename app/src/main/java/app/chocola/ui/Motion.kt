package app.chocola.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import app.chocola.data.Diagnostics
import kotlinx.coroutines.CancellationException

internal val LocalDiagnostics = staticCompositionLocalOf<Diagnostics?> { null }

internal suspend fun <T> Diagnostics?.traceMotion(category: String, fields: Map<String, Any?> = emptyMap(), block: suspend () -> T): T {
    val span = this?.begin("motion", fields = fields + ("category" to category))
    var outcome = "done"
    try { span?.stage("running"); return block() }
    catch (error: CancellationException) { outcome = "cancelled"; throw error }
    catch (error: Exception) { outcome = "failed"; throw error }
    finally { span?.finish(outcome) }
}

internal object Motion {
    const val Page = 300
    const val Overlay = 280
    const val Close = 220
    const val Feedback = 160
    const val Expand = 220
    val Travel = CubicBezierEasing(.22f, .78f, .18f, 1f)
}

/** A dismissal is committed once. Its next action runs after the old surface exits. */
internal class DismissSequence {
    var closing = false
        private set
    private var finished = false
    private var next: (() -> Unit)? = null

    fun begin(action: (() -> Unit)? = null): Boolean {
        if (closing) return false
        closing = true
        next = action
        return true
    }

    fun finish(dismiss: () -> Unit) {
        if (!closing || finished) return
        finished = true
        dismiss()
        next?.invoke()
        next = null
    }
}

class OverlayClose internal constructor(private val request: ((() -> Unit)?) -> Unit) : () -> Unit {
    internal var parallel: ((() -> Unit) -> Unit)? = null
    override fun invoke() = request(null)
    fun after(action: () -> Unit) = request(action)
    fun alongside(action: () -> Unit) { parallel?.invoke(action) ?: after(action) }
}

@Composable internal fun TabContent(selected: Int, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    val saved = rememberSaveableStateHolder()
    AnimatedContent(selected, modifier.clipToBounds(), transitionSpec = {
        val direction = if (targetState > initialState) 1 else -1
        slideInHorizontally(tween(Motion.Close, easing = Motion.Travel)) { it * direction } togetherWith
            slideOutHorizontally(tween(Motion.Close, easing = Motion.Travel)) { -it * direction }
    }, label = "tabs") { tab ->
        saved.SaveableStateProvider(tab) { Box(Modifier.fillMaxSize()) { content(tab) } }
    }
}
