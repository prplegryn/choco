package app.chocola

import android.app.Application
import app.chocola.data.Diagnostics
import app.chocola.data.Repository
import app.chocola.ui.SoftShadows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

class ChocolaApp : Application() {
    private val createdAt = System.nanoTime()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: Repository
        private set
    override fun onCreate() {
        val started = System.nanoTime()
        super.onCreate()
        val logs = Diagnostics(this)
        logs.event("startup.application.begin", mapOf("loggerSetupMs" to (System.nanoTime() - started) / 1_000_000.0))
        val density = resources.displayMetrics.density
        scope.launch(Dispatchers.Default) {
            val warming = System.nanoTime()
            runCatching { SoftShadows.warm(density) }.onFailure { logs.error("startup.shadows.failed", it) }
            logs.event("startup.shadows.ready", mapOf("ms" to (System.nanoTime() - warming) / 1_000_000.0))
        }
        repository = Repository(this, scope, logs)
        scope.launch(Dispatchers.Default) {
            repository.state.map { it.tasks to it.activeProcessCount }.distinctUntilChanged().collect { (tasks, processes) -> logs.work(tasks, processes) }
        }
        app.chocola.ui.MathTypesetter.initialize(this) { fields -> logs.event("math.layout", fields) }
        logs.event("startup.application.ready", mapOf("ms" to (System.nanoTime() - started) / 1_000_000.0,
            "sinceApplicationObjectMs" to (System.nanoTime() - createdAt) / 1_000_000.0))
    }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (::repository.isInitialized) repository.logs.trimMemory(level) }
    override fun onLowMemory() { super.onLowMemory(); if (::repository.isInitialized) repository.logs.trimMemory(80) }
}
