package app.chocola.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable fun MemoriesPage(state: AppState, repository: Repository, onBack: () -> Unit, edit: (Memory?) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(onBack, actions = { IconButton("plus", "添加记忆") { edit(null) } })
        Box(Modifier.padding(horizontal = 20.dp)) { ToggleEntry("自动保存有用信息", state.preferences.autoMemory) { value -> scope.launch { repository.preferences(state.preferences.copy(autoMemory = value)) } } }
        Field("", query, { query = it }, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), placeholder = "搜索记忆")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            items(state.memories.filter { it.name.contains(query, true) || it.kind.contains(query, true) }, key = { it.id }) { m ->
                Press(Modifier.fillMaxWidth().heightIn(min = 78.dp), onClick = { edit(m) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(if (m.fields.any { it.secret }) "key" else "memory", color = if (m.enabled) Palette.Ink else Palette.Faint)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Txt(m.name, weight = FontWeight.Medium, maxLines = 1, color = if (m.enabled) Palette.Ink else Palette.Secondary)
                            Txt("${m.kind}  ${m.fields.size} 个字段", size = 12, color = Palette.Secondary, maxLines = 1)
                        }
                        if (!m.enabled) Txt("已停用", size = 12, color = Palette.Secondary)
                        Spacer(Modifier.width(10.dp)); Glyph("right", Modifier.size(16.dp), Palette.Secondary)
                    }
                }
            }
            if (state.memories.isEmpty()) item { ActionButton("添加记忆", Modifier.fillMaxWidth().padding(top = 16.dp)) { edit(null) } }
        }
    }
}
@Composable fun MemoryEditor(initial: Memory, state: AppState, repository: Repository, onBack: () -> Unit, onSave: (Memory) -> Unit) {
    val scope = rememberCoroutineScope()
    var json by rememberSaveable(initial.id) { mutableStateOf(initial.json().toString()) }
    val memory = Memory.from(JSONObject(json))
    fun update(value: Memory) { json = value.json().toString() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var editingField by remember { mutableIntStateOf(-2) }
    fun save() {
        if (memory.name.isBlank() || memory.fields.isEmpty()) { repository.notice("请填写名称并添加字段"); return }
        onSave(memory)
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
        TopBar(onBack, actions = {
            if (tab == 0) IconButton("plus", "添加字段") { editingField = -1 }
            IconButton("check", "保存记忆", onClick = ::save)
        })
        Segments(listOf("内容", "使用范围"), tab, { tab = it }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        TabContent(tab, Modifier.weight(1f)) { selected ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected == 0) {
                item { Field("记忆名称", memory.name, { update(memory.copy(name = it)) }, placeholder = "例如 我的服务器") }
                item { Entry(text = "资料类型", value = memory.kind) { dialog = "kind" } }
                items(memory.fields.size) { index ->
                    val field = memory.fields[index]
                    Press(Modifier.fillMaxWidth().heightIn(min = 66.dp), onClick = { editingField = index }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Txt(memoryFieldLabel(memory.kind, field.name), size = 14, weight = FontWeight.Medium)
                                Txt(if (field.secret && field.value.isNotBlank()) "已保存" else field.value.ifBlank { "点击填写" }, size = 13, color = Palette.Secondary, maxLines = 2)
                            }
                            Glyph(if (field.secret) "lock" else "right", Modifier.size(17.dp), Palette.Secondary)
                        }
                    }
                }
                item { Entry("plus", "添加自定义字段") { editingField = -1 } }
            } else {
                item { ToggleEntry("允许在对话中使用", memory.enabled) { update(memory.copy(enabled = it)) } }
                item { Entry(text = "适用会话", value = if (memory.scope.isBlank()) "所有会话" else state.sessions.firstOrNull { it.id == memory.scope }?.title ?: "指定会话") { dialog = "scope" } }
                item { ToggleEntry("允许工具使用凭据", memory.toolsAllowed, detail = "供连接服务器、GitHub 等操作使用") { update(memory.copy(toolsAllowed = it)) } }
                if (memory.kind != "服务器" && memory.fields.any { it.secret }) item {
                    Field("允许发送凭据的域名", memory.domain, { update(memory.copy(domain = it.trim().lowercase())) }, placeholder = if (memory.kind == "GitHub") "api.github.com" else "例如 api.example.com")
                }
                if (state.memories.any { it.id == memory.id }) item { Entry("trash", "删除这条记忆", danger = true) { dialog = "delete" } }
            }
        }
        }
    }
    when (dialog) {
        "kind" -> ChoiceSheet(listOf("资料", "服务器", "GitHub", "自定义"), memory.kind, { dialog = null }) { kind ->
            val defaults = when (kind) {
                "服务器" -> listOf(MemoryField("host", ""), MemoryField("port", "22"), MemoryField("username", "root"), MemoryField("password", "", true), MemoryField("private_key", "", true))
                "GitHub" -> listOf(MemoryField("username", ""), MemoryField("token", "", true))
                else -> listOf(MemoryField("内容", ""))
            }
            val fields = if (memory.fields.all { it.value.isBlank() }) defaults else memory.fields + defaults.filter { d -> memory.fields.none { it.name == d.name } }
            update(memory.copy(kind = kind, fields = fields, domain = if (kind == "GitHub" && memory.domain.isBlank()) "api.github.com" else memory.domain))
        }
        "scope" -> Sheet({ dialog = null }) { close ->
            Entry(text = "所有会话", value = if (memory.scope.isBlank()) "已选择" else "") { update(memory.copy(scope = "")); close() }
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(state.sessions.sortedByDescending { it.updated }, key = { it.id }) { s ->
                    Entry(text = s.title, value = if (s.id == memory.scope) "已选择" else "") { update(memory.copy(scope = s.id)); close() }
                }
            }
        }
        "delete" -> ConfirmSheet("删除“${memory.name}”？", "删除", { dialog = null }) { scope.launch { repository.deleteMemory(memory.id); onBack() } }
    }
    if (editingField >= -1) {
        val index = editingField
        val initialField = memory.fields.getOrNull(index) ?: MemoryField("", "")
        FieldEditor(initialField, memoryFieldLabel(memory.kind, initialField.name).takeIf { it != initialField.name }, { editingField = -2 }, onDelete = if (index < 0) null else ({ update(memory.copy(fields = memory.fields.filterIndexed { i, _ -> i != index })); editingField = -2 })) { field ->
            if (field.name.isBlank()) { repository.notice("请填写字段名称"); return@FieldEditor false }
            if (memory.fields.filterIndexed { i, _ -> i != index }.any { it.name == field.name }) { repository.notice("字段名称不能重复"); return@FieldEditor false }
            update(memory.copy(fields = if (index < 0) memory.fields + field else memory.fields.mapIndexed { i, f -> if (i == index) field else f }))
            true
        }
    }
}
@Composable private fun FieldEditor(initial: MemoryField, fixedLabel: String?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (MemoryField) -> Boolean) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var value by rememberSaveable { mutableStateOf(initial.value) }
    var secret by rememberSaveable { mutableStateOf(initial.secret) }
    Sheet(onDismiss) { close ->
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (fixedLabel == null) Field("字段名称", name, { name = it }, placeholder = "例如 联系邮箱")
            Field(fixedLabel ?: "内容", value, { value = it; if (secret) Redactor.register(it) }, secret = secret, lines = 6)
            ToggleEntry("隐藏内容", secret, detail = "密钥、密码等内容不会直接提供给模型") { secret = it; if (it) Redactor.register(value) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onDelete != null) ActionButton("删除", Modifier.weight(1f), solid = false) { close.after(onDelete) }
                ActionButton("保存", Modifier.weight(1f), enabled = name.isNotBlank()) { if (onSave(MemoryField(name.trim(), value, secret))) close() }
            }
        }
    }
}

private fun memoryFieldLabel(kind: String, name: String): String = if (kind in setOf("服务器", "GitHub")) when (name) {
    "host" -> "服务器地址"; "port" -> "SSH 端口"; "username" -> "用户名"; "password" -> "密码"; "private_key" -> "SSH 私钥"; "token" -> "GitHub Token"; else -> name
} else name
