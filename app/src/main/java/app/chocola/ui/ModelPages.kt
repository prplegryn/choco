package app.chocola.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chocola.agent.ContextPlanner
import app.chocola.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun InfoPair(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(label, Modifier.weight(1f), size = 13, color = Palette.Secondary)
        Txt(value, Modifier.widthIn(max = 230.dp), size = 13, maxLines = 2)
    }
}
internal fun effortLabel(value: String, provider: Provider? = null) = if (value.isBlank()) "模型默认" else provider?.let { ThinkingControls.label(it, value) } ?: ThinkingControls.label(value)

@Composable fun ProviderEditor(initial: Provider, repository: Repository, onBack: () -> Unit, onSave: (Provider) -> Unit) {
    val scope = rememberCoroutineScope()
    val providers by remember(repository, scope) { repository.state.project(scope) { it.providers } }.collectAsStateWithLifecycle()
    var json by rememberSaveable(initial.id) { mutableStateOf(initial.json().toString()) }
    val p = Provider.from(JSONObject(json))
    fun change(value: Provider) { json = value.json().toString() }
    if (ImageModels.isImage(p)) {
        val state by repository.state.collectAsStateWithLifecycle()
        ImageSettingsPage(state, repository, onBack, p, onSave, onUseChat = { change(p.copy(image = null)) })
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var purposeOpen by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val family = ModelCatalog.family(p)
    val profile = ModelCatalog.profile(p)
    val context = LocalContext.current
    fun execute(block: suspend () -> Unit) {
        if (working) return
        scope.launch { working = true; try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { result = Redactor.clean(e.message ?: "请求失败"); sheet = "result" } finally { working = false } }
    }
    fun save() {
        runCatching {
            require(p.model.isNotBlank()) { "请填写模型 ID" }
            require(p.maxTokens in 128..(profile?.maxOutput ?: 1_000_000)) { "输出预算超出此预设的范围" }
            require(p.contextWindow == 0 || p.contextWindow > p.maxTokens) { "上下文窗口须大于输出预算" }
            if (family == "anthropic") require(p.thinkingBudget == 0 || p.thinkingBudget < p.maxTokens) { "思考预算须小于输出预算" }
            ModelCatalog.parameters(p)
            onSave(p)
        }.onFailure { repository.notice(it.message) }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
        TopBar(onBack, actions = { IconButton("check", "保存模型", onClick = ::save) })
        Segments(listOf("模型", "生成", "上下文", "高级"), tab, { tab = it }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        TabContent(tab, Modifier.weight(1f)) { selected ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (selected) {
                    0 -> {
                        if (!CodexWire.isCodex(p)) item { Column {
                            Entry("image", "模型用途", "对话") { purposeOpen = !purposeOpen }
                            InlineMenu(purposeOpen) {
                                Entry("", "对话", "已选") { purposeOpen = false }
                                Entry("", "绘图") { purposeOpen = false; change(p.copy(image = ImageSettings())) }
                            }
                        } }
                        if (CodexWire.isCodex(p)) item { InfoPair("模型", p.model) } else item { Field("模型 ID", p.model, { name ->
                            val next = p.copy(model = name.trim(), family = if (providers.none { it.id == p.id }) "auto" else p.family)
                            change(if (providers.none { it.id == p.id }) ModelCatalog.profile(next)?.apply(next)?.copy(protocol = p.protocol) ?: next else next)
                        }) }
                        item { ActionButton(if (working) "测试中" else "测试模型", Modifier.fillMaxWidth(), solid = false, enabled = !working && p.model.isNotBlank()) { execute { result = "模型可用\n${repository.engine.api.test(p)}"; sheet = "result" } } }
                        if (profile != null) item { InfoPair("参数适配", profile.name) }
                    }
                    1 -> {
                        item { Entry(text = "参数方案", value = "按用途应用") { sheet = "workload" } }
                        if (!CodexWire.isCodex(p)) item { Entry(text = "输出预算", value = "${p.maxTokens} token") { sheet = "output" } }
                        if (ModelCatalog.thinkingOptions(p).size > 1) item { Entry(text = "思考模式", value = mapOf("default" to "模型默认", "enabled" to "开启", "disabled" to "关闭", "adaptive" to "自适应", "between_tools" to "仅工具间思考")[p.thinking].orEmpty()) { sheet = "thinking" } }
                        if (ModelCatalog.efforts(p).isNotEmpty() && p.thinking != "disabled") item { Entry(text = if (CodexWire.isCodex(p)) "思考深度" else "推理强度", value = effortLabel(p.reasoningEffort, p)) { sheet = "effort" } }
                        if ((family == "qwen" && p.thinking != "disabled") || (family == "anthropic" && p.thinking == "enabled")) item { Entry(text = "思考预算", value = if (p.thinkingBudget == 0) "模型默认" else "${p.thinkingBudget} token") { sheet = "thinkingBudget" } }
                        if (ModelCatalog.supportsTemperature(p)) item { Entry(text = "随机程度", value = p.temperature?.toString() ?: "模型默认") { sheet = "temperature" } }
                        if (ModelCatalog.supportsTopP(p)) item { Entry(text = "核采样", value = p.topP?.toString() ?: "模型默认") { sheet = "topP" } }
                        if (profile != null) item { Txt(profile.note, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), size = 12, color = Palette.Secondary) }
                    }
                    2 -> {
                        item { Entry(text = "上下文窗口", value = if (p.contextWindow == 0) "自动  ${ContextPlanner.window(p) / 1000}K" else "${p.contextWindow / 1000}K token") { sheet = "context" } }
                        item { ToggleEntry("自动整理上下文", p.autoCompact) { change(p.copy(autoCompact = it)) } }
                        if (p.autoCompact) {
                            item { SettingSlider("整理阈值", p.compactPercent.toFloat(), 35f..90f, "${p.compactPercent}%") { change(p.copy(compactPercent = it.toInt())) } }
                            item { Entry(text = "优先保留最近轮次", value = "${p.recentTurns} 轮") { sheet = "turns" } }
                            item { Entry(text = "摘要输出预算", value = "${p.summaryTokens} token") { sheet = "summary" } }
                        }
                    }
                    3 -> {
                        if (!CodexWire.isCodex(p)) item { Entry(text = "接口协议", value = p.protocol.label) { sheet = "protocol" } }
                        if (!CodexWire.isCodex(p)) item { Entry(text = "参数适配", value = if (p.family == "auto") "自动识别" else ModelCatalog.families[p.family].orEmpty()) { sheet = "family" } }
                        if (p.protocol == Protocol.CHAT) item { Entry(text = "输出限制字段", value = p.tokenParameter.ifBlank { "不发送" }) { sheet = "token" } }
                        item { Entry(text = "无响应超时", value = "${p.timeoutSeconds} 秒") { sheet = "timeout" } }
                        item { Entry(text = "附加请求参数", value = "JSON") { sheet = "extra" } }
                        if (profile != null) item { Entry("external", "模型官方说明") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profile.url))) } }
                        if (!CodexWire.isCodex(p) && providers.any { it.id == p.id }) item { Entry("trash", "删除模型", danger = true) { sheet = "delete" } }
                    }
                }
            }
        }
    }
    when (sheet) {
        "protocol" -> ChoiceSheet(Protocol.entries.map { it.label }, p.protocol.label, { sheet = null }) { label -> change(p.copy(protocol = Protocol.entries.first { it.label == label })) }
        "workload" -> ChoiceSheet(listOf("日常聊天", "任务执行", "深度分析", "默认推理与采样"), null, { sheet = null }) { label -> change(if (label == "默认推理与采样") p.copy(reasoningEffort = "", thinking = "default", temperature = null, topP = null, thinkingBudget = 0) else ModelCatalog.applyWorkload(p, mapOf("日常聊天" to "daily", "任务执行" to "agent", "深度分析" to "deep").getValue(label))) }
        "thinking" -> {
            val options = ModelCatalog.thinkingOptions(p)
            ChoiceSheet(options.keys.toList(), options.entries.firstOrNull { it.value == p.thinking }?.key, { sheet = null }) { change(p.copy(thinking = options.getValue(it))) }
        }
        "effort" -> {
            val values = listOf("") + ModelCatalog.efforts(p)
            ChoiceSheet(values.map { effortLabel(it, p) }, effortLabel(p.reasoningEffort, p), { sheet = null }) { label -> change(p.copy(reasoningEffort = values.first { effortLabel(it, p) == label })) }
        }
        "family" -> ChoiceSheet(listOf("自动识别") + ModelCatalog.families.values, if (p.family == "auto") "自动识别" else ModelCatalog.families[p.family], { sheet = null }) { label -> change(p.copy(family = ModelCatalog.families.entries.firstOrNull { it.value == label }?.key ?: "auto")) }
        "token" -> ChoiceSheet(listOf("max_tokens", "max_completion_tokens", "不发送"), p.tokenParameter.ifBlank { "不发送" }, { sheet = null }) { change(p.copy(tokenParameter = if (it == "不发送") "" else it)) }
        "output" -> NumberSetting("输出预算", "token", p.maxTokens.toString(), 128..(profile?.maxOutput ?: 1_000_000), { sheet = null }) { change(p.copy(maxTokens = it.toInt())) }
        "context" -> NumberSetting("上下文窗口", "token", ContextPlanner.window(p).toString(), 8192..4_000_000, { sheet = null }) { change(p.copy(contextWindow = it.toInt())) }
        "summary" -> NumberSetting("摘要输出预算", "token", p.summaryTokens.toString(), 4096..65536, { sheet = null }) { change(p.copy(summaryTokens = it.toInt())) }
        "turns" -> NumberSetting("保留最近轮次", "轮", p.recentTurns.toString(), 1..8, { sheet = null }) { change(p.copy(recentTurns = it.toInt())) }
        "thinkingBudget" -> NumberSetting("思考预算", "token，0 为默认", p.thinkingBudget.toString(), 0..1_000_000, { sheet = null }) { change(p.copy(thinkingBudget = it.toInt())) }
        "timeout" -> NumberSetting("无响应超时", "秒", p.timeoutSeconds.toString(), 30..1200, { sheet = null }) { change(p.copy(timeoutSeconds = it.toInt())) }
        "temperature", "topP" -> {
            val temperature = sheet == "temperature"
            DecimalParameter(if (temperature) "随机程度" else "核采样", if (temperature) p.temperature else p.topP, if (temperature) 0.0..2.0 else ModelCatalog.topPRange(p), { sheet = null }) { if (temperature) change(p.copy(temperature = it)) else change(p.copy(topP = it)) }
        }
        "extra" -> {
            var value by rememberSaveable { mutableStateOf(p.extraBody) }
            Sheet({ sheet = null }) { close -> Field("附加请求参数", value, { value = it }, lines = 10, placeholder = "{}")
                Spacer(Modifier.height(16.dp)); ActionButton("保存", Modifier.fillMaxWidth()) { runCatching { ModelCatalog.parameters(p.copy(extraBody = value)); close.after { change(p.copy(extraBody = value)) } }.onFailure { repository.notice(it.message) } }
            }
        }
        "result" -> Sheet({ sheet = null }) { close -> Txt(result, Modifier.padding(vertical = 12.dp), size = 14); ActionButton("关闭", Modifier.fillMaxWidth(), onClick = close) }
        "delete" -> ConfirmSheet("删除此模型？", "删除", { sheet = null }) { execute { repository.deleteProvider(p.id); onBack() } }
    }
}

@Composable private fun DecimalParameter(label: String, initial: Double?, range: ClosedFloatingPointRange<Double>, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial?.toString().orEmpty()) }
    Sheet(onDismiss) { close ->
        Field(label, value, { value = it }, placeholder = "留空使用模型默认", keyboard = KeyboardType.Decimal)
        Spacer(Modifier.height(16.dp)); ActionButton("保存", Modifier.fillMaxWidth(), enabled = value.isBlank() || value.toDoubleOrNull()?.let { it in range } == true) { val result = value.toDoubleOrNull(); close.after { onSave(result) } }
    }
}
