package app.chocola.ui

import android.os.Build
import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.chocola.agent.BackgroundAccess
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chocola.BuildConfig
import app.chocola.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

@Composable internal fun HeadersEditor(initial: Map<String, String>, onDismiss: () -> Unit, environment: Boolean = false, onSave: (Map<String, String>) -> Unit) {
    var headers by remember { mutableStateOf(initial.toList().ifEmpty { listOf("" to "") }) }
    Sheet(onDismiss) { close ->
        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(headers.size) { index ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Field("", headers[index].first, { value -> headers = headers.mapIndexed { i, h -> if (i == index) value to h.second else h } }, Modifier.weight(1f), placeholder = if (environment) "变量名称" else "请求头名称")
                        IconButton("close", "删除请求头") { headers = headers.filterIndexed { i, _ -> i != index } }
                    }
                    Field("", headers[index].second, { value -> headers = headers.mapIndexed { i, h -> if (i == index) h.first to value else h } }, placeholder = if (environment) "变量内容" else "请求头内容", secret = true)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("添加", Modifier.weight(1f), solid = false) { headers = headers + ("" to "") }
            ActionButton("保存", Modifier.weight(1f)) { close.after { onSave(headers.filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second }) } }
        }
    }
}
@Composable fun BackgroundPage(repository: Repository, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val battery = remember(revision) { BackgroundAccess.batteryExempt(context) }
    val notifications = remember(revision) { BackgroundAccess.notificationsEnabled(context) }
    val restricted = remember(revision) { BackgroundAccess.restricted(context) }
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure { repository.notice("系统未提供此页面，请在应用信息中调整"); runCatching { context.startActivity(BackgroundAccess.appSettingsIntent(context)) } } }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(onBack)
        Column(Modifier.padding(20.dp)) {
            Entry("battery", "电池优化豁免", if (battery) "已允许" else "待允许") { open(if (battery) BackgroundAccess.appSettingsIntent(context) else BackgroundAccess.batteryIntent(context)) }
            if (BackgroundAccess.vivoPowerManagement()) Entry("background", "vivo 后台耗电", "系统管理", detail = "选择允许后台高耗电") { BackgroundAccess.openVendorBattery(context) }
            Entry("notification", "任务通知", if (notifications) "已开启" else "未开启", detail = "查看运行状态，并可直接停止任务") { open(BackgroundAccess.notificationIntent(context)) }
            Entry("settings", "系统后台管理", if (restricted) "受限制" else "前往设置", detail = "自启动与后台运行") { open(BackgroundAccess.appSettingsIntent(context)) }
        }
    }
}
@Composable internal fun NumberSetting(label: String, unit: String, initial: String, range: IntRange, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    Sheet(onDismiss) { close ->
        Field("$label（$unit）", value, { value = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number)
        Spacer(Modifier.height(16.dp))
        ActionButton("保存", Modifier.fillMaxWidth(), enabled = value.toIntOrNull()?.let { it in range } == true) { close.after { onSave(value) } }
    }
}
