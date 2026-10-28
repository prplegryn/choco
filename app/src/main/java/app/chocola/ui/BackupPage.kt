package app.chocola.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import kotlinx.coroutines.*

@Composable fun DataPage(repository: Repository, state: AppState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, Uri>?>(null) }
    var stopBefore by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun whenIdle(action: () -> Unit) { if (repository.state.value.hasBackgroundWork) stopBefore = action else action() }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.chocola.backup")) { uri -> if (uri != null) pending = "export" to uri }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pending = "restore" to uri }
    SettingsScaffold(onBack) {
        item { GroupLabel("完整备份") }
        item { Entry("download", "导出全部数据", detail = "包含密钥与全部本地文件，使用密码加密") { whenIdle { create.launch("Choco-${java.time.LocalDate.now()}.chocola") } } }
        item { Entry("backup", "从备份恢复", detail = "替换当前全部数据，系统权限需重新授予") { whenIdle { open.launch(arrayOf("*/*")) } } }
        item { InfoPair("会话", state.sessions.size.toString()) }
        item { InfoPair("模型", state.providers.size.toString()) }
        item { InfoPair("记忆与凭据", state.memories.size.toString()) }
        item { InfoPair("Skill", state.skills.size.toString()) }
        item { InfoPair("MCP", state.mcpServers.size.toString()) }
    }
    stopBefore?.let { action -> ConfirmSheet("停止运行中的任务后继续？", "停止并继续", { stopBefore = null }) {
        scope.launch { repository.engine.stopAll(); action() }
    } }
    pending?.let { (mode, uri) -> key(uri, mode) {
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        val exporting = mode == "export"
        Sheet({ pending = null }) { close ->
            Field("备份密码", password, { password = it }, secret = true, placeholder = if (exporting) "至少 8 个字符" else "输入导出时的密码")
            if (exporting) { Spacer(Modifier.height(12.dp)); Field("再次输入", confirmation, { confirmation = it }, secret = true) }
            Spacer(Modifier.height(16.dp))
            ActionButton(if (exporting) "加密并导出" else "替换全部数据并恢复", Modifier.fillMaxWidth(),
                enabled = password.length >= 8 && (!exporting || password == confirmation)) {
                val secret = password.toCharArray()
                password = ""; confirmation = ""
                close.after { scope.launch {
                    try {
                        if (exporting) {
                            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output -> repository.exportBackup(secret, output) } }
                            repository.notice("完整备份已导出")
                        } else {
                            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { repository.restoreBackup(it, secret) } }
                            repository.notice("完整数据已恢复")
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { repository.logs.error("backup.ui.failed", e); repository.notice(Redactor.clean(e.message ?: "备份操作失败")) }
                    finally { secret.fill('\u0000') }
                } }
            }
        }
    } }
}
