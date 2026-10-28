package app.chocola.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chocola.api.ApiClient
import app.chocola.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Image models have their own controls; text token/reasoning parameters never enter image requests. */
@Composable internal fun ImageSettingsPage(state: AppState, repository: Repository, onBack: () -> Unit,
    initial: Provider? = null, onSaveModel: ((Provider) -> Unit)? = null, onUseChat: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val seed = remember(initial?.id) {
        if (initial != null) (initial.image ?: ImageSettings()).copy(model = initial.model, vendorId = initial.vendorId)
        else runCatching { state.currentProvider?.let { ImageModels.resolve(state, it).second } }.getOrNull()
            ?: state.preferences.image.let { config -> config.copy(vendorId = config.vendorId.takeIf { id -> state.vendors.any { it.id == id } }.orEmpty()) }
    }
    var document by rememberSaveable(initial?.id) { mutableStateOf(seed.json().toString()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var customSize by rememberSaveable { mutableStateOf(seed.size.takeIf { it !in standardSizes }.orEmpty()) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val config = remember(document) { ImageSettings.from(JSONObject(document)) }
    val vendor = state.vendors.firstOrNull { it.id == config.vendorId }
    val account = initial ?: vendor?.request() ?: state.currentProvider ?: state.vendors.firstOrNull()?.request()
    val codex = account?.let(CodexWire::isCodex) == true
    fun change(value: ImageSettings) { document = value.json().toString() }
    fun model(value: String) {
        val profile = state.providers.firstOrNull { it.model == value && (it.vendorId == account?.vendorId) }?.image ?: ImageSettings()
        change(profile.copy(model = value, vendorId = config.vendorId))
    }
    fun save() { scope.launch {
        try {
            val saved = if (codex) config.copy(transport = "images", routingModel = "") else config
            require(saved.model.isNotBlank()) { "请选择绘图模型" }
            ImageModels.validate(saved)
            if (initial != null && onSaveModel != null) onSaveModel(initial.copy(model = saved.model.trim(), image = saved.copy(vendorId = "", model = "")))
            else {
                val chosen = vendor ?: state.vendors.firstOrNull { it.id == account?.vendorId } ?: error("请先添加服务商")
                if (chosen.id == CodexWire.VENDOR) require(config.model in CodexImageModels.names) { "请选择 Codex 支持的绘图模型" }
                val existing = state.providers.firstOrNull { it.vendorId == chosen.id && it.model == config.model.trim() }
                val target = existing ?: if (chosen.id == CodexWire.VENDOR) CodexModel(config.model.trim()).provider() else chosen.model(config.model.trim())
                repository.saveProvider(target.copy(image = saved.copy(vendorId = "", model = "")))
                repository.preferences(repository.state.value.preferences.copy(image = saved.copy(model = saved.model.trim(), vendorId = if (state.currentProvider?.let(CodexWire::isCodex) == true && saved.vendorId.isBlank()) chosen.id else saved.vendorId)))
                onBack()
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { repository.notice(Redactor.clean(error.message ?: "保存失败")) }
    } }
    SettingsScaffold(onBack, actions = { IconButton("check", "保存绘图设置", onClick = ::save) },
        header = { Segments(listOf("模型", "画面"), tab, { tab = it }) }) {
        if (tab == 0) {
            if (initial != null && !ImageModels.isImage(initial.model) && onUseChat != null) item {
                ImageOption("模型用途", "image", linkedMapOf("image" to "绘图", "chat" to "对话")) { if (it == "chat") onUseChat() }
            }
            if (initial == null) item {
                ImageOption("服务商", config.vendorId, linkedMapOf("" to "跟随会话") +
                    state.vendors.associate { it.id to it.label }) { id -> change(config.copy(vendorId = id, model = "")); fetched = emptyList() }
            }
            if (!codex) item { Field("图片模型", config.model, { change(config.copy(model = it)) }, placeholder = "模型 ID") }
            val choices = (state.providers.filter { ImageModels.isImage(it) && account != null && ImageModels.sameAccount(it, account) }.map { it.model } +
                (if (codex) CodexImageModels.names else emptyList()) + fetched.filter { ImageModels.isImage(it) }).distinct()
            if (codex && initial != null) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt("图片模型", size = 16)
                    Spacer(Modifier.weight(1f))
                    Txt(initial.model, Modifier.widthIn(max = 218.dp), size = 13, color = Palette.Secondary, maxLines = 1)
                }
            } else if (choices.isNotEmpty()) item { ImageOption("选择模型", config.model, choices.associateWith { it }, ::model) }
            item { Entry("refresh", if (fetching) "正在获取模型" else "获取模型", onClick = {
                if (!fetching && account != null) scope.launch {
                    fetching = true
                    try { fetched = if (codex) { repository.codexAccounts.refresh(); repository.codexAccounts.models() } else ApiClient(repository.logs).models(account)
                        if (fetched.none { ImageModels.isImage(it) }) repository.notice("此服务商未列出图片模型，可直接填写模型 ID") }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { repository.notice(Redactor.clean(error.message ?: "获取失败")) }
                    finally { fetching = false }
                }
            }) }
            if (!codex) item { ImageOption("调用方式", config.transport, linkedMapOf("images" to "图片接口", "responses" to "对话绘图")) { change(config.copy(transport = it)) } }
            if (!codex && config.transport == "responses") item {
                val routers = state.providers.filter { !ImageModels.isImage(it) && it.vendorId == account?.vendorId }.map { it.model }
                ImageOption("调度模型", config.routingModel, linkedMapOf("" to "跟随会话") + routers.associateWith { it }) { change(config.copy(routingModel = it)) }
            }
            item { ImageOption("请求时限", config.timeoutSeconds.toString(), listOf(120, 300, 600, 900, 1800).associate { it.toString() to "${it / 60} 分钟" }) { change(config.copy(timeoutSeconds = it.toInt())) } }
            item { Entry("sliders", "附加参数", if (extra) "收起" else "") { extra = !extra } }
            item { InlineMenu(extra) { Field("JSON", config.extraBody, { change(config.copy(extraBody = it)) }, lines = 7) } }
            if (!codex && initial != null && state.providers.any { it.id == initial.id }) item { Entry("trash", "删除模型", danger = true) { deleting = true } }
        } else {
            val sizes = when (ImageModels.family(config.model)) {
                "gpt1" -> standardSizes.filterKeys { it in setOf("auto", "1024x1024", "1536x1024", "1024x1536") }
                "dalle3" -> linkedMapOf("auto" to "自动", "1024x1024" to "方图  1024x1024", "1792x1024" to "横图  1792x1024", "1024x1792" to "竖图  1024x1792")
                "dalle2" -> linkedMapOf("auto" to "自动", "256x256" to "256x256", "512x512" to "512x512", "1024x1024" to "1024x1024")
                else -> standardSizes
            }
            item { ImageOption("尺寸", if (config.size in sizes) config.size else "custom", sizes + (if (ImageModels.supportsCustomSize(config.model)) mapOf("custom" to "自定义") else emptyMap())) {
                change(config.copy(size = if (it == "custom") customSize.ifBlank { "1536x864" } else it))
            } }
            if (config.size !in sizes) item { Field("宽x高", config.size, { customSize = it; change(config.copy(size = it)) }, placeholder = "1536x864") }
            item { ImageOption("质量", config.quality, ImageModels.qualities(config.model).associateWith { qualityLabels[it] ?: it }) { change(config.copy(quality = it)) } }
            val modern = ImageModels.family(config.model) !in setOf("dalle2", "dalle3")
            if (modern) item { ImageOption("格式", config.format, linkedMapOf("png" to "PNG", "jpeg" to "JPEG", "webp" to "WebP")) {
                change(config.copy(format = it, background = if (it == "jpeg" && config.background == "transparent") "opaque" else config.background))
            } }
            if (modern) item {
                ImageOption("背景", config.background, linkedMapOf("auto" to "自动", "opaque" to "不透明", "transparent" to "透明")) {
                    change(config.copy(background = it, format = if (it == "transparent" && config.format == "jpeg") "png" else config.format))
                }
            }
            if (modern && config.format != "png") item { SettingSlider("压缩质量", config.compression.toFloat(), 0f..100f, "${config.compression}%") { change(config.copy(compression = it.toInt())) } }
            item { ImageOption("数量", config.count.toString(), (1..10).associate { it.toString() to "$it 张" }) { change(config.copy(count = it.toInt())) } }
            if (ImageModels.supportsFidelity(config.model)) item { ImageOption("参考图保真", config.inputFidelity, linkedMapOf("auto" to "默认", "low" to "标准", "high" to "高")) { change(config.copy(inputFidelity = it)) } }
            if (ImageModels.family(config.model).startsWith("gpt")) item { ImageOption("内容审核", config.moderation, linkedMapOf("auto" to "默认", "low" to "较低")) { change(config.copy(moderation = it)) } }
        }
    }
    if (deleting && initial != null && !codex) ConfirmSheet("删除此图片模型？", "删除", { deleting = false }) {
        scope.launch {
            try { repository.deleteProvider(initial.id); onBack() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { repository.notice(Redactor.clean(error.message ?: "删除失败")) }
        }
    }
}

@Composable private fun ImageOption(label: String, value: String, choices: Map<String, String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Entry("", label, choices[value] ?: value) { open = !open }
        InlineMenu(open) {
            choices.forEach { (key, title) -> Entry("", title, if (key == value) "已选" else "") { onSelect(key); open = false } }
        }
    }
}
private val standardSizes = linkedMapOf("auto" to "自动", "1024x1024" to "方图  1024x1024", "1536x1024" to "横图  1536x1024",
    "1024x1536" to "竖图  1024x1536", "2048x2048" to "方图  2K", "3840x2160" to "横图  4K", "2160x3840" to "竖图  4K")
private val qualityLabels = mapOf("auto" to "自动", "low" to "草稿", "medium" to "标准", "high" to "高", "xhigh" to "很高", "max" to "最高", "standard" to "标准", "hd" to "高清")
