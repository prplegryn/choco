package app.chocola.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chocola.data.*
import kotlinx.coroutines.*

@Composable internal fun CodexPage(state: AppState, repository: Repository, onBack: () -> Unit, editModel: (Provider) -> Unit) {
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<CodexAccountInfo?>(null) }
    var chosen by remember { mutableStateOf<CodexAccountInfo?>(null) }
    var models by remember { mutableStateOf(false) }
    val codex = state.codex
    fun work(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { repository.notice(Redactor.clean(e.message.orEmpty())) } } }
    LaunchedEffect(Unit) { delay(1000); repository.codexAccounts.refresh() }
    SettingsScaffold(onBack, actions = {
        CodexRefreshButton(codex.refreshing) { work { repository.codexAccounts.refresh() } }
        IconButton("plus", "添加 Codex 账号", enabled = codex.login == null || codex.login.error.isNotBlank()) { repository.codexAccounts.startLogin() }
    }) {
        items(codex.accounts, key = { it.id }) { account ->
            CodexAccountCard(account, Modifier.animateItem(), onEnabled = { enabled -> work { repository.codexAccounts.setEnabled(account.id, enabled) } }, onMore = { chosen = account })
        }
        if (codex.accounts.isEmpty()) item("add") { Entry("plus", "添加 Codex 账号") { repository.codexAccounts.startLogin() } }
        val count = state.providers.count(CodexWire::isCodex)
        if (count > 0) item("models") { Entry("models", "可用模型", "$count 个") { models = true } }
        if (codex.error.isNotBlank()) item("error") { Txt(codex.error, Modifier.padding(12.dp), size = 13, color = Palette.Red) }
    }
    chosen?.let { account -> Sheet({ chosen = null }) { close ->
        Txt(account.email, Modifier.padding(12.dp), size = 14, maxLines = 2)
        Entry(if (account.enabled) "stop" else "play", if (account.enabled) "停用账号" else "启用账号") { close.after { work { repository.codexAccounts.setEnabled(account.id, !account.enabled) } } }
        if (account.needsLogin) Entry("key", "重新登录") { close.after { repository.codexAccounts.startLogin() } }
        Entry("trash", "移除账号", danger = true) { close.after { deleting = account } }
    } }
    deleting?.let { account -> ConfirmSheet("移除 ${account.email}？", "移除账号", { deleting = null }) { work { repository.codexAccounts.remove(account.id) } } }
    codex.login?.let { login -> CodexLoginSheet(login, cancel = { work { repository.codexAccounts.cancelLogin() } }, retry = { work { repository.codexAccounts.cancelLogin(); repository.codexAccounts.startLogin() } }) }
    if (models) Sheet({ models = false }) { close ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
            items(state.providers.filter(CodexWire::isCodex), key = { it.id }) { provider -> Entry(if (ImageModels.isImage(provider)) "image" else "codex", provider.model) { close.after { editModel(provider) } } }
        }
    }
}

@Composable private fun CodexAccountCard(account: CodexAccountInfo, modifier: Modifier = Modifier, onEnabled: (Boolean) -> Unit, onMore: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 7.dp).background(Color(0xFFF5F5F7), RoundedCornerShape(22.dp)).padding(start = 17.dp, end = 13.dp, top = 12.dp, bottom = 15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Txt(account.email, size = 14, weight = FontWeight.SemiBold, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(planLabel(account.plan), Modifier.background(Color.White, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp), size = 10, weight = FontWeight.Medium)
                    if (!account.enabled) Txt("已停用", size = 10, color = Palette.Secondary)
                    if (account.needsLogin) Txt("需重新登录", size = 10, color = Palette.Red)
                }
            }
            Toggle(account.enabled, onEnabled)
            IconButton("more", "账号操作", Modifier.size(34.dp), floating = false, onClick = onMore)
        }
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(19.dp)) {
            CodexQuotaColumn("五小时", account.quota.fiveHours, Modifier.weight(1f))
            CodexQuotaColumn("每周", account.quota.weekly, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(end = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Glyph("repeat", Modifier.size(12.dp), Palette.Secondary)
            Txt("重置卡", size = 11, color = Palette.Secondary)
            Txt(account.quota.resetCredits?.let { "$it 张" } ?: "未提供", Modifier.weight(1f), size = 11)
            AnimatedVisibility(account.refreshing, enter = fadeIn(tween(120)), exit = fadeOut(tween(180))) { CodexRefreshMark(Modifier.size(12.dp)) }
        }
        if (account.error.isNotBlank()) Txt(account.error, Modifier.padding(end = 5.dp), size = 11, color = Palette.Red, maxLines = 3, lineHeight = 17)
    }
}

@Composable private fun CodexQuotaColumn(label: String, window: CodexWindow?, modifier: Modifier) {
    val remaining by animateFloatAsState((window?.remaining?.div(100f) ?: 0f), tween(550, easing = Motion.Travel), label = "codexQuota")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Txt(label, size = 11, color = Palette.Secondary)
            Txt(window?.let { "剩余 ${it.remaining}%" } ?: "未提供", size = 11, weight = FontWeight.Medium)
        }
        Canvas(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))) {
            drawRect(Color(0xFFDEDEE3))
            drawRect(if ((window?.remaining ?: 100) <= 10) Color(0xFFB85F49) else Color(0xFF38383F), size = androidx.compose.ui.geometry.Size(size.width * remaining, size.height))
        }
        Txt(window?.resetsAt?.takeIf { it > 0 }?.let { quotaReset(it) } ?: "—", size = 10, color = Palette.Secondary, maxLines = 1)
    }
}

@Composable private fun CodexRefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    Press(Modifier.size(48.dp), radius = 24.dp, enabled = !refreshing, description = if (refreshing) "正在刷新账号" else "刷新账号", onClick = onClick) {
        Box(Modifier.size(44.dp).floatingSurface(22.dp, compact = true), contentAlignment = Alignment.Center) {
            if (refreshing) CodexRefreshMark(Modifier.size(19.dp)) else Glyph("refresh", Modifier.size(21.dp))
        }
    }
}
@Composable private fun CodexRefreshMark(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "codexRefresh")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "rotation")
    Glyph("refresh", modifier.graphicsLayer { rotationZ = angle }, Palette.Secondary)
}

@Composable private fun CodexLoginSheet(login: CodexLoginInfo, cancel: () -> Unit, retry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(login.expiresAt) { while (login.expiresAt > System.currentTimeMillis()) { delay(1000); now = System.currentTimeMillis() } }
    Sheet(cancel) { _ ->
        if (login.starting) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CodexRefreshMark(Modifier.size(17.dp)); Spacer(Modifier.width(10.dp)); Txt("获取授权地址", size = 14)
            }
        } else if (login.error.isNotBlank()) {
            Txt(login.error, Modifier.padding(12.dp), size = 14, lineHeight = 23)
            Spacer(Modifier.height(12.dp)); ActionButton("重新获取", Modifier.fillMaxWidth(), onClick = retry)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt("授权地址", Modifier.weight(1f), size = 12, color = Palette.Secondary)
                    IconButton("copy", "复制授权地址", Modifier.size(38.dp)) { clipboard.setText(AnnotatedString(login.url)) }
                }
                SelectionContainer { Txt(login.url.removePrefix("https://"), size = 14, maxLines = 2) }
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt("一次性代码", size = 12, color = Palette.Secondary)
                        SelectionContainer { androidx.compose.foundation.text.BasicText(login.code, style = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, color = Palette.Ink, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)) }
                    }
                    IconButton("copy", "复制一次性代码", Modifier.size(42.dp)) { clipboard.setText(AnnotatedString(login.code)) }
                }
                val seconds = ((login.expiresAt - now).coerceAtLeast(0) / 1000)
                Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodexRefreshMark(Modifier.size(13.dp)); Txt("等待授权", Modifier.weight(1f), size = 12, color = Palette.Secondary)
                    Txt("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", size = 11, color = Palette.Secondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp)); ActionButton("取消", Modifier.fillMaxWidth(), solid = false, onClick = cancel)
    }
}

private fun planLabel(plan: String) = when (plan.lowercase()) { "free" -> "Free"; "plus" -> "Plus"; "pro" -> "Pro"; "team", "business" -> "Business"; "enterprise" -> "Enterprise"; "edu" -> "Edu"; "go" -> "Go"; else -> plan.ifBlank { "套餐未知" } }
private fun quotaReset(time: Long): String {
    if (time <= System.currentTimeMillis()) return "待刷新"
    val date = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault())
    val format = if (date.toLocalDate() == java.time.LocalDate.now()) "HH:mm" else "MM-dd HH:mm"
    return date.format(java.time.format.DateTimeFormatter.ofPattern(format)) + " 重置"
}
