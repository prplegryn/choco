package app.chocola.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chocola.BuildConfig
import app.chocola.data.*
import kotlinx.coroutines.launch

/** Lists continue behind the floating controls, with feathering at the actual screen edges. */
@Composable internal fun SettingsScaffold(onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    SettingsLayout(onBack, actions, if (header != null) 70.dp else 0.dp, header) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable internal fun SettingsLayout(onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
    headerHeight: androidx.compose.ui.unit.Dp = 0.dp, header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    compactTop: Boolean = false,
    content: @Composable (PaddingValues) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val bottom = with(density) { WindowInsets.navigationBars.union(WindowInsets.ime).getBottom(this).toDp() }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        content(PaddingValues(start = 20.dp, end = 20.dp, top = top + 76.dp + headerHeight, bottom = bottom + 34.dp))
        Canvas(Modifier.fillMaxSize()) {
            val depth = (top + (if (compactTop) 76.dp else 108.dp) + headerHeight).toPx().coerceAtMost(size.height)
            val solid = if (compactTop) 0f else ((top + 68.dp + headerHeight).toPx() / depth).coerceIn(0f, .98f)
            val topBrush = if (compactTop) Brush.verticalGradient(listOf(Color.White, Color.Transparent), endY = depth)
                else Brush.verticalGradient(0f to Color.White, solid to Color.White, 1f to Color.Transparent, endY = depth)
            drawRect(topBrush, size = Size(size.width, depth))
            val foot = (bottom + 40.dp).toPx().coerceAtMost(size.height / 2f)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = size.height - foot, endY = size.height), topLeft = Offset(0f, size.height - foot), size = Size(size.width, foot))
        }
        Column {
            TopBar(onBack, actions = actions)
            if (header != null) Box(Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 20.dp, vertical = 7.dp)) { header() }
        }
        if (footer != null) Box(Modifier.align(Alignment.BottomCenter)) { footer() }
    }
}

private data class SettingLink(val group: String, val icon: String, val title: String, val route: String, val keywords: String = "")
private val settingLinks = listOf(
    SettingLink("对话", "models", "模型服务", "providers", "厂家 品牌 余额 密钥 账户 API 模型"),
    SettingLink("对话", "codex", "Codex 账号", "codex", "Codex 反代 登录 授权 账号 账户 套餐 额度 重置卡"),
    SettingLink("对话", "tone", "对话风格", "style", "回复 风格 语气 亲切 热情 标题 列表 表情 详略 指令"),
    SettingLink("外观", "display", "显示与动效", "display", "文字 字体 字号 背景 壁纸 液态玻璃 悬浮 状态指示器 动效"),
    SettingLink("能力", "image", "图片生成", "image-settings", "画图 图片 生成 编辑 模型 质量 尺寸 透明 压缩"),
    SettingLink("能力", "memory", "记忆与凭据", "memories", "服务器 GitHub Token 资料"),
    SettingLink("能力", "skills", "技能", "skills", "技能 导入 SKILL.md"),
    SettingLink("能力", "mcp", "工具与 MCP", "mcp", "服务 工具 HTTP stdio 插件"),
    SettingLink("运行", "shield", "工具权限", "permissions", "授权 确认 自动编辑 规划 跳过确认"),
    SettingLink("运行", "battery", "后台与通知", "background", "通知 耗电 电池 自启动"),
    SettingLink("数据", "backup", "备份与恢复", "data", "导出 恢复 迁移 全部 完整 备份 数据")
)

private val displaySearchLinks = listOf(
    SettingLink("显示", "font", "文字大小", "font", "字体 字号 阅读"),
    SettingLink("显示", "wallpaper", "聊天背景", "chat-background", "壁纸 图片 蒙层"),
    SettingLink("显示", "glass", "悬浮控件", "floating-style", "液态玻璃 模糊 白色 折射"),
    SettingLink("显示", "indicator", "状态指示器", "status-style", "字体颜色 文字颜色 动效 药丸 呼吸 动画")
)

@Composable fun SettingsPage(state: AppState, onBack: () -> Unit, navigate: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query) {
        if (query.isBlank()) settingLinks else {
            val nested = displaySearchLinks.filter { (it.title + it.keywords).contains(query.trim(), true) }
            settingLinks.filter { (it.title + it.keywords).contains(query.trim(), true) && (it.route != "display" || nested.isEmpty()) } + nested
        }
    }
    SettingsScaffold(onBack, header = { Field("", query, { query = it }, placeholder = "搜索设置") }) {
        shown.groupBy { it.group }.forEach { (group, links) ->
            item(key = group) { GroupLabel(group) }
            items(links, key = { it.route }) { link ->
                Entry(link.icon, link.title, when (link.route) {
                    "providers" -> "${state.vendors.size} 个服务商"
                    "codex" -> "${state.codex.accounts.count { it.enabled }} 个账号已启用"
                    "memories" -> "${state.memories.size} 项"
                    "skills" -> "${state.skills.count { it.enabled }} 项已启用"
                    "mcp" -> "${state.mcpServers.count { it.enabled }} 个已启用"
                    "permissions" -> state.preferences.defaultAccess.label
                    else -> ""
                }) { navigate(link.route) }
            }
        }
        if (shown.isEmpty()) item { Txt("没有匹配的设置", Modifier.padding(12.dp), size = 14, color = Palette.Secondary) }
        if (query.isBlank()) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Txt("版本", size = 12, color = Palette.Secondary); Txt("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", size = 12, color = Palette.Secondary)
            }
        }
    }
}

@Composable internal fun DisplayPage(state: AppState, repository: Repository, onBack: () -> Unit, initialFont: Boolean = false, navigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var font by remember { mutableStateOf(initialFont) }
    SettingsScaffold(onBack) {
        item { GroupLabel("显示") }
        item { Entry("font", "文字大小", "${(state.preferences.fontScale / FontSizes.Default * 100).toInt()}%") { font = true } }
        item { Entry("wallpaper", "聊天背景") { navigate("chat-background") } }
        item { Entry("glass", "悬浮控件", if (state.preferences.floating.style == "glass") "液态玻璃" else "柔和白色") { navigate("floating-style") } }
        item { Entry("indicator", "状态指示器", statusStyles[state.preferences.pill.style].orEmpty()) { navigate("status-style") } }
    }
    if (font) {
        var value by remember { mutableFloatStateOf(state.preferences.fontScale) }
        Sheet({ font = false }) { close ->
            CompositionLocalProvider(LocalAppFontScale provides value) {
                Txt("清晰地交流，自然地阅读。", Modifier.padding(horizontal = 12.dp, vertical = 24.dp), size = 16, lineHeight = 27)
            }
            SettingSlider("文字大小", value, .8f..1.4f, "${(value / FontSizes.Default * 100).toInt()}%") { value = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("默认", Modifier.weight(1f), solid = false) { value = FontSizes.Default }
                ActionButton("应用", Modifier.weight(1f)) { close.after { scope.launch { repository.preferences(repository.state.value.preferences.copy(fontScale = value)) } } }
            }
        }
    }
}

@Composable fun StylePage(state: AppState, repository: Repository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var document by rememberSaveable { mutableStateOf(state.preferences.reply.json().toString()) }
    var instructions by rememberSaveable { mutableStateOf(state.preferences.instructions) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var toneOpen by remember { mutableStateOf(false) }
    val reply = remember(document) { ReplyPreferences.from(org.json.JSONObject(document)) }
    fun change(value: ReplyPreferences) { document = value.json().toString() }
    val tones = linkedMapOf("natural" to "自然", "friendly" to "友好", "professional" to "专业", "direct" to "直接", "curious" to "探索")
    SettingsScaffold(onBack, actions = { IconButton("check", "保存交流偏好") { scope.launch {
        repository.preferences(repository.state.value.preferences.copy(reply = reply, instructions = instructions)); onBack()
    } } }, header = { Segments(listOf("风格语气", "内容表达"), tab, { tab = it }) }) {
        if (tab == 0) {
            item { Entry("tone", "交流风格", tones[reply.tone].orEmpty()) { toneOpen = true } }
            item { ReplyLevel("亲切程度", reply.warmth, listOf("克制", "适中", "亲切")) { change(reply.copy(warmth = it)) } }
            item { ReplyLevel("热情程度", reply.enthusiasm, listOf("平静", "适中", "积极")) { change(reply.copy(enthusiasm = it)) } }
            item { ReplyLevel("回答详略", reply.detail, listOf("简短", "适中", "充分")) { change(reply.copy(detail = it)) } }
        } else {
            item { ReplyLevel("标题与列表", reply.structure, listOf("较少", "按需", "较多")) { change(reply.copy(structure = it)) } }
            item { ReplyLevel("表情符号", reply.emoji, listOf("不用", "少量", "适量")) { change(reply.copy(emoji = it)) } }
            item { Field("自定义指令", instructions, { instructions = it }, placeholder = "你希望 AI 如何交流", lines = 6) }
        }
    }
    if (toneOpen) ChoiceSheet(tones.values.toList(), tones[reply.tone].orEmpty(), { toneOpen = false }) { title -> change(reply.copy(tone = tones.entries.first { it.value == title }.key)) }
}

@Composable private fun ReplyLevel(label: String, selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(label, size = 14); Segments(labels, selected, onSelect)
    }
}
