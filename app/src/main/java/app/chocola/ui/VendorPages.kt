package app.chocola.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun VendorsPage(state: AppState, repository: Repository, onBack: () -> Unit, edit: (ApiVendor?) -> Unit) {
    val vendors = remember(state.vendors) { state.vendors.sortedWith(compareBy<ApiVendor> { it.label.lowercase() }.thenBy { it.host }) }
    LaunchedEffect(vendors.map { it.balanceKey }) {
        vendors.forEach { vendor -> launch { repository.refreshBalance(vendor.request()) } }
    }
    SettingsScaffold(onBack, actions = { IconButton("plus", "添加服务商") { edit(null) } }) {
        item { GroupLabel("服务商") }
        items(vendors, key = { it.id }) { vendor ->
            val balance = if (vendor.id == CodexWire.VENDOR) ModelBalance("${state.codex.accounts.count { it.enabled }} 个账号已启用") else state.balances[vendor.balanceKey] ?: ModelBalance()
            val count = state.providers.count { it.vendorId == vendor.id }
            Press(Modifier.fillMaxWidth().padding(vertical = 4.dp), radius = 18.dp, onClick = { edit(vendor) }) {
                Column(Modifier.fillMaxWidth().background(Palette.Fill, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Txt(vendor.label, Modifier.weight(1f), size = 16, weight = FontWeight.Medium, maxLines = 1)
                        Txt(balance.text, size = 13, color = if (balance.failed) Palette.Secondary else Palette.Ink, maxLines = 1)
                        Glyph("right", Modifier.size(12.dp), Palette.Secondary)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Txt("$count 个模型", Modifier.weight(1f), size = 12, color = Palette.Secondary)
                        Txt((if (vendor.id == CodexWire.VENDOR) "" else vendor.host) + if (vendor.apiKey.length >= 12) "  …${vendor.apiKey.takeLast(4)}" else "", Modifier.widthIn(max = 210.dp), size = 11, color = Palette.Secondary, maxLines = 1)
                    }
                }
            }
        }
        if (vendors.isEmpty()) item { ActionButton("添加服务商", Modifier.fillMaxWidth()) { edit(null) } }
    }
}

private data class VendorPreset(val label: String, val endpoint: String, val protocol: Protocol)
// Reuse the verified endpoint defaults; this is a choice of vendor, never a global model catalogue.
private val vendorPresets = ModelCatalog.models.distinctBy { it.endpoint.trimEnd('/') }.map { profile ->
    VendorPreset(ModelAccounts.brand(Provider(baseUrl = profile.endpoint)), profile.endpoint, profile.protocol)
} + listOf(VendorPreset("OpenRouter", "https://openrouter.ai/api/v1", Protocol.CHAT),
    VendorPreset("硅基流动", "https://api.siliconflow.cn/v1", Protocol.CHAT))

@Composable internal fun VendorPage(initial: ApiVendor, state: AppState, repository: Repository, onBack: () -> Unit, editModel: (Provider) -> Unit) {
    val scope = rememberCoroutineScope()
    var json by rememberSaveable(initial.id) { mutableStateOf(initial.json().toString()) }
    val vendor = ApiVendor.from(JSONObject(json))
    val saved = state.vendors.firstOrNull { it.id == initial.id }
    val models = state.providers.filter { it.vendorId == vendor.id }
    var tab by rememberSaveable(initial.id) { mutableIntStateOf(if (saved == null) 1 else 0) }
    var custom by rememberSaveable(initial.id) { mutableStateOf(initial.brand.isBlank() && vendorPresets.none { it.endpoint.trimEnd('/') == initial.baseUrl.trimEnd('/') }) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<List<String>?>(null) }
    fun change(value: ApiVendor) { json = value.json().toString() }
    fun execute(block: suspend () -> Unit) {
        if (working) return
        scope.launch {
            working = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { repository.logs.error("vendor.ui.failed", e); repository.notice(Redactor.clean(e.message ?: "操作失败")) }
            finally { working = false }
        }
    }
    suspend fun persist() { repository.saveVendor(vendor) }
    fun addModel() { execute { persist(); editModel(vendor.model()) } }
    fun fetchModels() { execute {
        persist()
        val existing = repository.state.value.providers.filter { it.vendorId == vendor.id }.map { it.model }.toSet()
        choosing = repository.engine.api.models(vendor.request()).distinct().filterNot { it in existing }
    } }
    val balance = state.balances[vendor.balanceKey]
    LaunchedEffect(saved?.balanceKey) { saved?.let { repository.refreshBalance(it.request()) } }
    SettingsLayout(onBack, actions = {
        if (tab == 1) IconButton("check", "保存服务商") { execute { persist(); tab = 0 } }
    }, headerHeight = 62.dp, header = {
        Segments(listOf("模型", "API"), tab, { tab = it }, Modifier.fillMaxWidth())
    }) { padding ->
        TabContent(tab, Modifier.fillMaxSize()) { selected ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { GroupLabel(vendor.label.takeIf { vendor.baseUrl.isNotBlank() } ?: "添加服务商") }
                if (selected == 0) {
                    if (saved != null) item {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(balance?.detail?.ifBlank { "余额" } ?: "余额", Modifier.weight(1f), size = 13, color = Palette.Secondary)
                            Txt(balance?.text ?: "查询中", size = 13, color = if (balance?.failed == true) Palette.Secondary else Palette.Ink)
                            IconButton("refresh", "刷新余额", Modifier.padding(start = 4.dp)) { execute { repository.refreshBalance(vendor.request(), force = true) } }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton(if (working) "获取中" else "获取模型", Modifier.weight(1f), solid = false, enabled = !working) { fetchModels() }
                            ActionButton("手动添加", Modifier.weight(1f), solid = false, enabled = !working) { addModel() }
                        }
                    }
                    items(models, key = { it.id }) { model ->
                        Entry(if (ImageModels.isImage(model)) "image" else "models", model.model, detail = if (ImageModels.isImage(model)) "绘图" else ModelCatalog.profile(model)?.purpose.orEmpty()) {
                            execute { persist(); editModel(repository.state.value.providers.first { it.id == model.id }) }
                        }
                    }
                } else {
                    item { Entry(text = "服务商", value = if (vendor.baseUrl.isBlank()) "选择服务商" else vendor.label) { sheet = "brand" } }
                    item { Field("API 地址", vendor.baseUrl, { change(vendor.copy(baseUrl = it.trim())) }, keyboard = KeyboardType.Uri) }
                    item { Field("API 密钥", vendor.apiKey, { Redactor.register(it); change(vendor.copy(apiKey = it.trim())) }, secret = true) }
                    item { Entry(text = "默认接口", value = vendor.protocol.label) { sheet = "protocol" } }
                    item { Entry(text = "请求头", value = "${vendor.headers.size} 项") { sheet = "headers" } }
                    if (custom) item {
                        Field("服务商名称", vendor.brand, { change(vendor.copy(brand = it)) }, placeholder = vendor.host)
                    }
                    if (saved != null) item { Entry("trash", "删除服务商", danger = true) { sheet = "delete" } }
                }
            }
        }
    }
    when (sheet) {
        "brand" -> Sheet({ sheet = null }) { close ->
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(vendorPresets, key = { it.endpoint }) { preset -> Entry(text = preset.label) { close.after {
                    custom = false
                    change(vendor.copy(brand = preset.label, baseUrl = preset.endpoint, protocol = preset.protocol))
                } } }
                item { Entry(text = "自定义服务商") { close.after { custom = true; change(vendor.copy(brand = "", baseUrl = "", protocol = Protocol.CHAT)) } } }
            }
        }
        "protocol" -> ChoiceSheet(Protocol.entries.map { it.label }, vendor.protocol.label, { sheet = null }) { label -> change(vendor.copy(protocol = Protocol.entries.first { it.label == label })) }
        "headers" -> HeadersEditor(vendor.headers, { sheet = null }) { change(vendor.copy(headers = it)) }
        "delete" -> ConfirmSheet(if (models.isEmpty()) "删除此服务商？" else "删除此服务商及其 ${models.size} 个模型？", "删除", { sheet = null }) { execute { repository.deleteVendor(vendor.id); onBack() } }
    }
    choosing?.let { names ->
        var chosen by remember(names) { mutableStateOf(emptySet<String>()) }
        var query by remember { mutableStateOf("") }
        Sheet({ choosing = null }) { close ->
            Field("", query, { query = it }, placeholder = "搜索模型")
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(names.filter { it.contains(query, true) }, key = { it }) { name -> ToggleEntry(name, name in chosen) { enabled -> chosen = if (enabled) chosen + name else chosen - name } }
                if (names.isEmpty()) item { Txt("没有可添加的模型", Modifier.padding(12.dp), size = 13, color = Palette.Secondary) }
            }
            ActionButton("添加 ${chosen.size} 个模型", Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty() && !working) { close.after {
                execute { repository.saveProviders(chosen.map(vendor::model)) }
            } }
        }
    }
}
