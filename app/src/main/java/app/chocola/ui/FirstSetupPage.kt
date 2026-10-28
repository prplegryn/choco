package app.chocola.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.chocola.agent.BackgroundAccess
import app.chocola.data.*
import kotlinx.coroutines.launch

@Composable internal fun FirstSetupPage(state: AppState, repository: Repository, onApi: () -> Unit, onRestore: () -> Unit,
    onNotifications: () -> Unit, onBackground: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var step by rememberSaveable { mutableIntStateOf(if (state.providers.isEmpty()) 0 else 1) }
    var revision by remember { mutableIntStateOf(0) }
    fun finish() { scope.launch { repository.preferences(repository.state.value.preferences.copy(setupCompleted = true)) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val notifications = remember(revision) { BackgroundAccess.notificationsEnabled(context) }
    val background = remember(revision) { BackgroundAccess.batteryExempt(context) }
    LaunchedEffect(state.providers.size) { if (state.providers.isNotEmpty()) step = 1 }
    BackHandler { if (step == 1) step = 0 else finish() }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(if (step > 0) ({ step = 0 }) else null, actions = {
            Press(Modifier.height(42.dp).floatingSurface(21.dp, true).padding(horizontal = 15.dp), radius = 21.dp, description = "稍后设置", onClick = ::finish) { Txt("稍后", size = 13) }
        })
        Spacer(Modifier.weight(1f))
        TabContent(step, Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 300.dp).padding(horizontal = 24.dp)) { page ->
            Column {
                if (page == 0) {
                    Entry("models", "添加服务商", if (state.vendors.isNotEmpty()) "${state.vendors.size} 个服务商" else "", onClick = onApi)
                    Entry("backup", "恢复完整备份", onClick = onRestore)
                } else {
                    Entry("notification", "允许通知", if (notifications) "已允许" else "", detail = "任务完成后接收结果", onClick = onNotifications)
                    Entry("battery", "允许后台运行", if (background) "已允许" else "", detail = "锁屏后继续回复和执行任务", onClick = onBackground)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ActionButton(if (step == 0) "继续" else "开始聊天", Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp)) { if (step == 0) step = 1 else finish() }
    }
}
