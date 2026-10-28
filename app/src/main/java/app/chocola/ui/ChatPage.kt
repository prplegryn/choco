package app.chocola.ui

import androidx.compose.animation.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chocola.agent.AgentEngine
import app.chocola.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

@Composable internal fun UserMessage(message: Message, onImage: (Attachment) -> Unit, running: Boolean, onContinue: () -> Unit, onLongPress: () -> Unit) {
    Column(Modifier.fillMaxWidth()
        .combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = onLongPress), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        val images = message.attachments.filter { it.mime.startsWith("image/") }
        val files = message.attachments.filterNot { it.mime.startsWith("image/") }
        if (images.isNotEmpty()) Box(Modifier.padding(start = 20.dp, end = if (images.size > 1) 0.dp else 20.dp)) { SentImages(images, onImage) }
        if (message.text.isNotBlank() || files.isNotEmpty()) {
        Column(Modifier.padding(horizontal = 20.dp).widthIn(max = (LocalConfiguration.current.screenWidthDp * .82f).dp).clip(RoundedCornerShape(22.dp, 22.dp, 7.dp, 22.dp))
            .background(Color(0xFFF0F0F2)).combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 15.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            files.forEach { a -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Glyph("file", Modifier.size(17.dp), Palette.Secondary); Txt(a.name, size = 13, color = Palette.Secondary, maxLines = 1)
            } }
            if (message.text.isNotBlank()) SelectionContainer { Txt(message.text, size = 16, lineHeight = 25) }
        }
        }
        AnimatedVisibility(message.status in setOf("queued", "withdrawn"), modifier = Modifier.padding(horizontal = 20.dp), enter = expandVertically(tween(190, easing = Motion.Travel)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(190, easing = Motion.Travel)) + fadeOut(tween(130))) {
        if (message.status == "queued") {
            Press(Modifier.heightIn(min = 30.dp).padding(horizontal = 5.dp), radius = 12.dp, description = if (running) "插入指令待接收" else "继续处理插入的指令",
                onClick = { if (!running) onContinue() else onLongPress() }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Glyph(if (running) "clock" else "play", Modifier.size(12.dp), Palette.Secondary)
                    Txt(if (running) "待接收" else "继续处理", size = 11, color = Palette.Secondary)
                }
            }
        } else if (message.status == "withdrawn") Txt("已撤回", size = 11, color = Palette.Secondary)
        }
    }
}
@Composable internal fun Composer(repository: Repository, state: AppState, attachments: List<Attachment>, panelOpen: String?, onPanel: (String, Rect) -> Unit, onPanelBounds: (String, Rect) -> Unit, onAttach: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit, onImage: (Attachment) -> Unit, onSend: suspend (String) -> Boolean) {
    var input by rememberSaveable(state.currentId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.current?.draft.orEmpty())) }
    val draft = input.text
    val running = state.currentId in state.tasks
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(state.current?.draft) { if (state.current?.draft?.isNotEmpty() == true && draft.isEmpty()) input = TextFieldValue(state.current?.draft.orEmpty()) }
    LaunchedEffect(draft) { delay(350); if (state.current != null) repository.saveDraft(state.currentId, draft) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun send() {
        if (submitting || (draft.isBlank() && attachments.isEmpty())) return
        val outgoing = input
        submitting = true
        repository.scope.launch {
            try {
                if (onSend(outgoing.text)) {
                    if (input == outgoing) input = TextFieldValue()
                    if (!running) { focus.clearFocus(force = true); keyboard?.hide() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { repository.logs.error("task.input.submit_failed", e); repository.notice(Redactor.clean(e.message ?: "消息未发送，请重试")) }
            finally { submitting = false }
        }
    }
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().floatingSurface(28.dp, allowGlass = false).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            androidx.compose.animation.AnimatedVisibility(attachments.isNotEmpty(),
                enter = androidx.compose.animation.expandVertically(tween(240, easing = Motion.Travel), expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(tween(180)),
                exit = androidx.compose.animation.shrinkVertically(tween(210, easing = Motion.Travel), shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(tween(130))) {
                ComposerAttachments(attachments, onImage, onRemoveAttachment) { label ->
                    val next = ImageReferences.append(input.text, label)
                    if (next != input.text) input = TextFieldValue(next, TextRange(next.length))
                }
            }
            BasicTextField(input, { input = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 9.dp)
                .onFocusChanged { repository.logs.event("ui.input.focus", mapOf("source" to "composer", "focused" to it.isFocused)) }
                .heightIn(min = 27.dp, max = 150.dp).onPreviewKeyEvent { event -> if (event.key == Key.Enter && event.isCtrlPressed && event.type == KeyEventType.KeyDown) { send(); true } else false },
                textStyle = TextStyle(fontSize = (16 * LocalAppFontScale.current).sp, lineHeight = (25 * LocalAppFontScale.current).sp, color = Palette.Ink, platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)), cursorBrush = SolidColor(Palette.Ink), maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
                readOnly = submitting,
                decorationBox = { inner -> Box { if (draft.isEmpty()) Txt("发消息", size = 16, color = Palette.Secondary); inner() } })
            val model = state.providers.firstOrNull { it.id == state.current?.providerId }
            ComposerActions(state.current?.access?.label ?: Access.ASK.label,
                model?.let { ThinkingControls.fraction(it, state.current) } ?: .5f, running,
                draft.isNotBlank() || attachments.isNotEmpty(), model != null,
                accessHidden = panelOpen == "access", thinkingHidden = panelOpen == "thinking", submitting = submitting,
                onAttach = onAttach, onThink = { bounds -> onPanel("thinking", bounds) },
                onAccess = { bounds -> onPanel("access", bounds) }, onSend = ::send, onStop = { repository.engine.stop(state.currentId) },
                onBounds = onPanelBounds)
        }
    }
}

@Composable fun SessionsDrawer(state: AppState, repository: Repository, onDismiss: () -> Unit, onNew: () -> Unit, onNavigate: (String) -> Unit, onSelected: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var scheduleEditor by remember { mutableStateOf<ScheduledTask?>(null) }
    var scheduleOptions by remember { mutableStateOf<ScheduledTask?>(null) }
    var scheduleDeleting by remember { mutableStateOf<ScheduledTask?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val schedules = remember(state.schedules, query) { state.schedules.filter { query.isBlank() || it.title.contains(query, true) || it.prompt.contains(query, true) }
        .sortedWith(compareByDescending<ScheduledTask> { it.lastStatus == "running" }.thenBy { if (it.enabled && it.nextAt > 0) it.nextAt else Long.MAX_VALUE }) }
    fun scheduleWork(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { repository.notice(e.message ?: "操作失败") } } }
    var selecting by remember { mutableStateOf(false) }
    val drawerExit = remember { java.util.concurrent.atomic.AtomicReference<OverlayClose>() }
    var options by remember { mutableStateOf<Session?>(null) }
    var optionAnchor by remember { mutableStateOf(Rect.Zero) }
    var rename by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    val sessions = remember(state.sessions, query) {
        state.sessions.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<Session> { it.pinned }.thenByDescending { it.updated })
    }
    val backdrop = rememberLayerBackdrop()
    val glass = LocalFloatingDesign.current.style == "glass" && android.os.Build.VERSION.SDK_INT >= 31
    CompositionLocalProvider(LocalControlBackdrop provides (if (glass) backdrop else LocalControlBackdrop.current)) {
    DrawerLayer(onDismiss) { close ->
        SideEffect { drawerExit.set(close) }
        androidx.compose.ui.layout.SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
            val loose = constraints.copy(minHeight = 0)
            val search = subcompose("search") {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().floatingSurface(23.dp, compact = true).height(46.dp).padding(start = 14.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Glyph("search", Modifier.size(18.dp), Palette.Secondary)
                        BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                            textStyle = TextStyle(fontSize = (14 * LocalAppFontScale.current).sp, lineHeight = (22 * LocalAppFontScale.current).sp, color = Palette.Ink,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)), cursorBrush = SolidColor(Palette.Ink),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            decorationBox = { inner -> Box { if (query.isBlank()) Txt(if (tab == 0) "搜索会话" else "搜索定时任务", size = 14, color = Palette.Secondary); inner() } })
                        if (query.isNotEmpty()) IconButton("close", "清除搜索", Modifier.size(34.dp)) { query = "" }
                        else if (tab == 1) IconButton("plus", "新建定时任务", Modifier.size(34.dp)) {
                            scheduleEditor = ScheduledTask(access = state.preferences.defaultAccess, startAt = (System.currentTimeMillis() / 60_000 + 2) * 60_000)
                        } else Spacer(Modifier.width(7.dp))
                    }
                    HistorySwitcher(tab) { tab = it; query = "" }
                }
            }.single().measure(loose)
            val footer = subcompose("footer") {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton("settings", "设置", floating = true) { close.alongside { onNavigate("settings") } }
                    IconButton("notebook", "笔记本", floating = true) { close.alongside { onNavigate("notebooks") } }
                    Press(Modifier.height(48.dp), radius = 22.dp, description = "新会话", onClick = { close.alongside(onNew) }) {
                        Row(Modifier.floatingSurface(22.dp, compact = true).height(44.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) { Glyph("edit", Modifier.size(17.dp)); Txt("新会话", size = 14, weight = FontWeight.Medium) }
                    }
                }
            }.single().measure(loose)
            val history = subcompose("history") {
                Box(Modifier.fillMaxSize().then(if (glass) Modifier.layerBackdrop(backdrop) else Modifier).background(Color(0xFFFAFAFB))) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = search.height.toDp(), bottom = footer.height.toDp())) {
                    if (tab == 1) {
                        item("alarm-permission") { SchedulePermissionCard(state) { runCatching { requestScheduleAlarm(context) }.onFailure { repository.notice(it.message) } } }
                        items(schedules, key = { "schedule:${it.id}" }) { task -> ScheduleRow(task, state,
                            onEdit = { scheduleEditor = task }, onMore = { bounds -> optionAnchor = bounds; scheduleOptions = task }) }
                        if (schedules.isEmpty()) item("schedule-empty") { Txt(if (query.isBlank()) "还没有定时任务" else "没有匹配的任务", Modifier.padding(16.dp), size = 13, color = Palette.Secondary) }

                    } else {
                    val today = java.time.LocalDate.now()
                    val zone = java.time.ZoneId.systemDefault()
                    val sections = sessions.groupBy { SessionHistory.section(it, today, zone) }
                    listOf("已置顶", "今天", "昨天", "最近七天", "更早").forEach { label ->
                        val group = sections[label].orEmpty()
                        if (group.isNotEmpty()) {
                            item("section:$label") { Txt(label, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 5.dp), size = 10, color = Palette.Secondary) }
                            items(group, key = { it.id }) { s -> SessionHistoryRow(s, state, onSelect = { if (!selecting) {
                                selecting = true
                                scheduleWork { try { onSelected(s.id); close() } finally { selecting = false } }
                            } }, onMore = { bounds -> optionAnchor = bounds; options = s }) }
                        }
                    }
                    if (sessions.isEmpty()) item { Txt(if (query.isBlank()) "还没有会话" else "没有匹配的会话", Modifier.padding(16.dp), size = 13, color = Palette.Secondary) }
                    }
                }
                DrawerEdgeVeils()
                }
            }.single().measure(constraints)
            layout(constraints.maxWidth, constraints.maxHeight) {
                history.place(0, 0); search.place(0, 0); footer.place(0, constraints.maxHeight - footer.height)
            }
        }
    }
    scheduleOptions?.let { selected ->
        val task = state.schedules.firstOrNull { it.id == selected.id } ?: selected
        AnchoredPanel(optionAnchor, "定时任务操作", 234, if (task.lastStatus == "running") 260 else 212, PanelGrowth.CONTEXT, { scheduleOptions = null }) { close ->
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                val actions = listOf("edit" to "编辑任务", "repeat" to if (task.enabled) "暂停计划" else "恢复计划", "context" to "查看执行结果") +
                    (if (task.lastStatus == "running") listOf("close" to "停止本次执行") else emptyList()) + listOf("trash" to "删除任务")
                actions.forEach { (icon, title) ->
                    Press(Modifier.fillMaxWidth().height(48.dp), radius = 11.dp, onClick = { close.after {
                        when (icon) {
                            "edit" -> scheduleEditor = task
                            "repeat" -> scheduleWork { repository.saveSchedule(task.copy(enabled = !task.enabled)) }
                            "context" -> { if (state.sessions.any { it.id == task.resultSessionId }) { scheduleWork { onSelected(task.resultSessionId); drawerExit.get()?.invoke() ?: onDismiss() } } else repository.notice(if (task.runCount > 0) "这次汇报记录已删除" else "任务尚未执行") }
                            "close" -> repository.engine.stop(task.resultSessionId)
                            else -> scheduleDeleting = task
                        }
                    } }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Glyph(icon, Modifier.size(17.dp), if (icon == "trash") Palette.Red else Palette.Ink)
                            Txt(title, size = 13, color = if (icon == "trash") Palette.Red else Palette.Ink)
                        }
                    }
                }
            }
        }
    }
    scheduleEditor?.let { task -> key(task.id) { ScheduleEditor(task, state, repository) { scheduleEditor = null } } }
    scheduleDeleting?.let { task -> ConfirmSheet("删除“${task.title}”的定时计划？已有执行结果会保留在历史会话中。", "删除", { scheduleDeleting = null }) { scheduleWork { repository.deleteSchedule(task.id) } } }
    options?.let { s -> AnchoredPanel(optionAnchor, "会话操作", 222, 164, PanelGrowth.CONTEXT, { options = null }) { close ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            listOf("pin" to if (s.pinned) "取消置顶" else "置顶", "edit" to "重命名", "trash" to "删除会话").forEach { (icon, title) ->
                Press(Modifier.fillMaxWidth().height(48.dp), radius = 11.dp, onClick = {
                    when (icon) {
                        "pin" -> close.after { scope.launch { repository.updateSession(s.id) { it.copy(pinned = !it.pinned) } } }
                        "edit" -> close.after { rename = s }
                        else -> close.after { deleting = s }
                    }
                }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(icon, Modifier.size(17.dp), if (icon == "trash") Palette.Red else Palette.Ink)
                        Txt(title, size = 13, color = if (icon == "trash") Palette.Red else Palette.Ink)
                    }
                }
            }
        }
    } }
    rename?.let { s ->
        var name by rememberSaveable(s.id) { mutableStateOf(s.title) }
        Sheet({ rename = null }) { close ->
            Field("", name, { name = it }, placeholder = "会话名称")
            Spacer(Modifier.height(16.dp)); ActionButton("保存", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) { scope.launch { repository.renameSession(s.id, name) }; close() }
        }
    }
    deleting?.let { s -> ConfirmSheet("删除“${s.title}”及其工作区？", "删除", { deleting = null }) { scope.launch { repository.deleteSession(s.id) } } }
    }
}

/** The list stays behind both floating rows; these masks are rooted at the window edges. */
@Composable private fun DrawerEdgeVeils() {
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density).toFloat()
    val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val paper = Color(0xFFFAFAFB)
        val top = (topInset + 155.dp.toPx()).coerceAtMost(size.height / 2)
        val bottom = (bottomInset + 104.dp.toPx()).coerceAtMost(size.height / 2)
        drawRect(Brush.verticalGradient(0f to paper, .32f to paper.copy(alpha = .98f), .64f to paper.copy(alpha = .76f), 1f to Color.Transparent, endY = top), size = Size(size.width, top))
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .46f to paper.copy(alpha = .72f), .78f to paper.copy(alpha = .97f), 1f to paper,
            startY = size.height - bottom, endY = size.height), topLeft = Offset(0f, size.height - bottom), size = Size(size.width, bottom))
    }
}
