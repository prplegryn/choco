package app.chocola.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun SkillsPage(state: AppState, repository: Repository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var instructions by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Skill?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun work(block: suspend () -> Unit) { if (!busy) scope.launch {
        busy = true
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { repository.logs.error("skill.ui.failed", e); repository.notice(Redactor.clean(e.message ?: "Skill 操作失败")) }
        finally { busy = false }
    } }
    fun edit(skill: Skill?) {
        if (skill == null) { source = ""; name = ""; description = ""; instructions = ""; editing = "new" }
        else work {
            source = repository.skillStore.document(skill.name)
            name = skill.name; description = skill.description
            instructions = source.replace("\r\n", "\n").substringAfter("\n---\n").trim()
            editing = skill.name
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) work {
        val count = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { repository.skillStore.import(it) } }
        repository.notice("已导入 $count 个 Skill")
    } }
    SettingsScaffold(onBack, actions = {
        IconButton("upload", "导入 Skill", enabled = !busy) { picker.launch(arrayOf("*/*")) }
        IconButton("plus", "创建 Skill", enabled = !busy) { edit(null) }
    }) {
        item { GroupLabel("Skill") }
        items(state.skills, key = { it.name }) { skill ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ToggleEntry(skill.name, skill.enabled, skill.description) { enabled -> work { repository.saveSkill(skill.copy(enabled = enabled)) } } }
                IconButton("edit", "编辑 ${skill.name}", Modifier.size(40.dp), enabled = !busy) { edit(skill) }
                IconButton("trash", "删除 ${skill.name}", Modifier.size(40.dp), enabled = !busy, color = Palette.Secondary) { deleting = skill }
            }
        }
        if (state.skills.isEmpty()) {
            item { Entry("upload", "导入 SKILL.md 或压缩包") { picker.launch(arrayOf("*/*")) } }
            item { Entry("plus", "创建 Skill") { edit(null) } }
        }
    }
    editing?.let { current ->
        Sheet({ if (!busy) editing = null }) { close ->
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { if (current == "new") Field("名称", name, { name = it.trim() }, placeholder = "例如 server-report") else InfoPair("名称", name) }
                item { Field("适用场景", description, { description = it }, lines = 3) }
                item { Field("指令", instructions, { instructions = it }, lines = 12) }
            }
            Spacer(Modifier.height(14.dp))
            ActionButton(if (busy) "正在保存" else "保存", Modifier.fillMaxWidth(), enabled = !busy && name.isNotBlank() && description.isNotBlank() && instructions.isNotBlank()) { work {
                val document = if (current == "new") SkillFormat.create(name, description, instructions) else SkillFormat.update(source, description, instructions)
                repository.skillStore.save(document, replacing = current.takeIf { it != "new" })
                close()
            } }
        }
    }
    deleting?.let { skill -> ConfirmSheet("删除 ${skill.name}？", "删除", { deleting = null }) { work { repository.deleteSkill(skill.name) } } }
}

@Composable internal fun McpPage(state: AppState, repository: Repository, onBack: () -> Unit, edit: (McpServer) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    fun work(id: String, block: suspend () -> Unit) { if (busy == null) scope.launch {
        busy = id
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { repository.logs.error("mcp.ui.failed", e); repository.notice(Redactor.clean(e.message ?: "MCP 操作失败")) }
        finally { busy = null }
    } }
    suspend fun importJson(text: String) {
        val servers = McpServer.import(text)
        servers.forEach { repository.saveMcp(it.copy(enabled = false)) }
        repository.notice("已导入 ${servers.size} 个服务")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) work("import") {
        val text = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { source ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = source.read(buffer); if (n < 0) break; require(out.size() + n <= 512_000) { "MCP 配置文件过大" }; out.write(buffer, 0, n) }
            out.toString("UTF-8")
        } }
        importJson(text)
    } }
    SettingsScaffold(onBack, actions = {
        IconButton("upload", "导入 MCP 配置", enabled = busy == null) { importing = true }
        IconButton("plus", "添加 MCP 服务", enabled = busy == null) { edit(McpServer()) }
    }) {
        item { GroupLabel("MCP") }
        items(state.mcpServers, key = { it.id }) { server ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ToggleEntry(server.name, server.enabled,
                    if (busy == server.id) "正在检查" else "${server.status}  ${JSONArray(server.tools).objects().count { it.getString("name") !in server.disabledTools }} 个工具") { enabled -> work(server.id) {
                    if (enabled) repository.saveMcp(repository.mcp.check(server.copy(enabled = true))) else repository.saveMcp(server.copy(enabled = false))
                } } }
                IconButton("edit", "编辑 ${server.name}", Modifier.size(42.dp), enabled = busy == null) { edit(server) }
            }
        }
        if (state.mcpServers.isEmpty()) item { ActionButton("添加 MCP 服务", Modifier.fillMaxWidth()) { edit(McpServer()) } }
    }
    if (importing) Sheet({ importing = false }) { close ->
        Field("MCP 配置", importText, { importText = it }, placeholder = "mcpServers JSON", lines = 9)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("选择文件", Modifier.weight(1f), solid = false) { close.after { picker.launch(arrayOf("*/*")) } }
            ActionButton("导入", Modifier.weight(1f), enabled = importText.isNotBlank()) { work("import") { importJson(importText); close() } }
        }
    }
}

@Composable internal fun McpEditor(initial: McpServer, repository: Repository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var json by rememberSaveable(initial.id) { mutableStateOf(initial.json().toString()) }
    val server = remember(json) { McpServer.from(JSONObject(json)) }
    fun change(value: McpServer) { json = value.json().toString() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var mapping by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    fun save() { if (!working) scope.launch {
        working = true
        try {
            server.validate()
            val ready = if (server.enabled) repository.mcp.check(server) else server.copy(status = "未检查")
            repository.saveMcp(ready); onBack()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { repository.logs.error("mcp.check.failed", e); repository.notice(Redactor.clean(e.message ?: "MCP 检查失败")) }
        finally { working = false }
    } }
    SettingsScaffold(onBack, actions = { IconButton("check", if (working) "正在检查" else "保存并检查 MCP", enabled = !working, onClick = ::save) },
        header = { Segments(listOf("服务", "工具"), tab, { tab = it }) }) {
        if (tab == 0) {
            item { Field("服务名称", server.name, { change(server.copy(name = it.take(60))) }) }
            item { Segments(listOf("远程 HTTP", "沙盒内服务"), if (server.transport == "stdio") 1 else 0, { change(server.copy(transport = if (it == 0) "http" else "stdio", tools = "[]", status = "未检查")) }, Modifier.padding(vertical = 8.dp)) }
            if (server.transport == "http") {
                item { Field("MCP 地址", server.url, { change(server.copy(url = it.trim())) }, placeholder = "https://example.com/mcp") }
                item { Field("访问令牌", server.token, { Redactor.register(it); change(server.copy(token = it)) }, placeholder = "可选", secret = true) }
                item { Entry("key", "请求头", "${server.headers.size} 项") { mapping = "headers" } }
            } else {
                item { Field("启动命令", server.command, { change(server.copy(command = it)) }, placeholder = "例如 npx、python3 或 uvx") }
                item { Field("参数（每行一个）", server.arguments.joinToString("\n"), { change(server.copy(arguments = it.lines().filter(String::isNotEmpty))) }, lines = 5) }
                item { Entry("key", "环境变量", "${server.environment.size} 项") { mapping = "env" } }
            }
            item { ToggleEntry("启用服务", server.enabled) { change(server.copy(enabled = it)) } }
            if (working) item { InfoPair("状态", "正在检查服务和工具") }
            else if (server.status == "可用") item { InfoPair("状态", "${JSONArray(server.tools).length()} 个工具可用") }
            if (repository.state.value.mcpServers.any { it.id == server.id }) item { Entry("trash", "删除服务", danger = true) { deleting = true } }
        } else {
            val tools = JSONArray(server.tools).objects()
            items(tools, key = { it.getString("name") }) { tool ->
                val name = tool.getString("name")
                ToggleEntry(tool.optString("title").ifBlank { name }, name !in server.disabledTools, tool.optString("description").take(180)) { enabled ->
                    change(server.copy(disabledTools = if (enabled) server.disabledTools - name else server.disabledTools + name))
                }
            }
            if (tools.isEmpty()) item { Entry("refresh", "检查并获取工具") { if (!working) scope.launch {
                working = true
                try { change(repository.mcp.check(server)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { repository.notice(Redactor.clean(e.message.orEmpty())) }
                finally { working = false }
            } } }
        }
    }
    mapping?.let { type -> HeadersEditor(if (type == "env") server.environment else server.headers, { mapping = null }, environment = type == "env") { values ->
        values.values.forEach(Redactor::register)
        change(if (type == "env") server.copy(environment = values) else server.copy(headers = values))
    } }
    if (deleting) ConfirmSheet("删除 ${server.name}？", "删除", { deleting = false }) { scope.launch { repository.deleteMcp(server.id); onBack() } }
}
