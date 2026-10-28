package app.chocola

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.chocola.ui.AppRoot
import app.chocola.agent.BackgroundAccess
import app.chocola.data.FrameMonitor
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var frames: FrameMonitor? = null
    private var startupDraw: StartupDrawGuard? = null
    private var telemetry: app.chocola.data.WindowTelemetry? = null
    private var sharedText by mutableStateOf<String?>(null)
    private var openConversationId by mutableStateOf<String?>(null)
    private val repository get() = (application as ChocolaApp).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val started = System.nanoTime()
        repository.logs.event("startup.activity_create.begin")
        repository.beginFirstFrame()
        configureWindow()
        repository.logs.event("startup.window.configured", mapOf("ms" to (System.nanoTime() - started) / 1_000_000.0))
        telemetry = app.chocola.data.WindowTelemetry(window, repository.logs).also { it.attach() }
        val decor = window.decorView
        startupDraw = StartupDrawGuard(decor, ready = { repository.firstFrameReady },
            onTimeout = {
                repository.logs.event("startup.draw_timeout", mapOf("loaded" to repository.state.value.loaded,
                    "screen" to repository.logs.screen))
                repository.logs.flush()
            },
            onFirstDraw = { elapsed, reason ->
                repository.logs.event("startup.first_draw", mapOf("elapsedMs" to elapsed, "release" to reason))
            })
        lifecycleScope.launch {
            repository.awaitFirstContent()
            reportFullyDrawn()
            repository.logs.event("startup.fully_drawn_reported")
        }
        frames = FrameMonitor(window, repository.logs, "activity")
        repository.logs.event("activity.created", mapOf("restored" to (savedInstanceState != null)))
        openScheduledSession(intent)
        sharedText = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent { AppRoot((application as ChocolaApp).repository, sharedText, openConversationId, { openConversationId = null }) { sharedText = null } }
        repository.logs.event("startup.content_attached", mapOf("ms" to (System.nanoTime() - started) / 1_000_000.0))
    }
    private fun configureWindow() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window.decorView.isForceDarkAllowed = false
        enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
    override fun onStart() { super.onStart(); repository.logs.visible(true); repository.logs.event("activity.started") }
    override fun onPause() { repository.logs.event("activity.paused"); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); repository.logs.event("activity.window_focus", mapOf("focused" to hasFocus)) }
    override fun onResume() {
        super.onResume(); frames?.start()
        repository.logs.event("activity.resumed", BackgroundAccess.snapshot(this) + mapOf("activeTasks" to repository.state.value.tasks.size, "processes" to repository.state.value.activeProcessCount))
        repository.logs.systemSnapshot("activity_resumed")
        (application as ChocolaApp).scope.launch { repository.engine.recover(); repository.scheduler.refresh() }
    }
    override fun onStop() {
        frames?.stop(); repository.logs.visible(false)
        repository.logs.event("activity.stopped", mapOf("changingConfiguration" to isChangingConfigurations, "activeTasks" to repository.state.value.tasks.size, "processes" to repository.state.value.activeProcessCount))
        repository.logs.flush(); super.onStop()
    }
    override fun onDestroy() {
        startupDraw?.close(); startupDraw = null
        telemetry?.close(); telemetry = null
        frames?.close(); frames = null
        repository.logs.event("activity.destroyed", mapOf("finishing" to isFinishing, "changingConfiguration" to isChangingConfigurations))
        super.onDestroy()
    }
    private fun openScheduledSession(intent: Intent) {
        openConversationId = intent.getStringExtra("sessionId")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openScheduledSession(intent)
        if (intent.action == Intent.ACTION_SEND) sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
    }
}
