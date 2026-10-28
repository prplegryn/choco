package app.chocola.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.chocola.data.*
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp

@Composable internal fun ChatPage(repository: Repository, timelineCache: ChatTimelineCache, state: AppState, attachments: List<Attachment>, onMenu: () -> Unit,
    onNew: () -> Unit, imeEnabled: Boolean = true, panelOpen: String?, onPanel: (String, Rect) -> Unit, onPanelBounds: (String, Rect) -> Unit, onAttach: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit, onTool: (String) -> Unit, onImage: (Attachment) -> Unit, onSend: suspend (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    val messageScope = key(state.currentId) { rememberCoroutineScope() }
    val messageFlow = remember(repository, state.currentId, messageScope) { repository.state.project(messageScope, 32, eager = true) { it.messages[state.currentId].orEmpty() } }
    val messages by messageFlow.collectAsStateWithLifecycle()
    val list = key(state.currentId) { rememberLazyListState() }
    val clipboard = LocalClipboardManager.current
    var actionMessage by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var openGroups by rememberSaveable(state.currentId) { mutableStateOf(listOf<String>()) }
    var openSteps by rememberSaveable(state.currentId) { mutableStateOf(listOf<String>()) }
    var readingTools by rememberSaveable(state.currentId) { mutableStateOf(false) }
    var followLatest by rememberSaveable(state.currentId) { mutableStateOf(true) }
    val positioned by remember(list) { derivedStateOf { list.layoutInfo.totalItemsCount > 0 } }
    val viewportLayout = remember(list) { ChatViewportLayout() }
    val layoutTrace = remember(list, repository) { ChatLayoutTrace(repository.logs, state.currentId) }
    var restoreAnchor by remember(list) { mutableStateOf(true) }
    var anchorKey by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
    var resumeEpoch by remember(list) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, list) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) resumeEpoch++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var enteringGroups by remember(state.currentId) { mutableStateOf(setOf<String>()) }
    var closingGroups by remember(state.currentId) { mutableStateOf(setOf<String>()) }
    val groupJobs = remember(state.currentId) { HashMap<String, kotlinx.coroutines.Job>() }
    var loadingOlder by remember { mutableStateOf(false) }
    val input = TimelineInput(state.currentId, messages, openGroups, openSteps, closingGroups, state.currentId in state.tasks, state.currentProvider?.let(ImageModels::isImage) == true)
    val initialFrame = remember(state.currentId) { timelineCache.peek(input) ?: TimelineFrame(state.currentId, false, emptyList()) }
    val frame by produceState(initialFrame, input) {
        val initial = !value.ready || value.session != state.currentId
        val started = System.nanoTime()
        if (initial) repository.logs.event("startup.timeline.start", mapOf("messages" to messages.size))
        value = withContext(Dispatchers.Default) {
            repository.logs.timing("timeline.dispatch_wait", System.nanoTime() - started)
            timelineCache.build(input)
        }
        if (initial) repository.logs.event("startup.timeline.ready", mapOf("items" to value.items.size, "elapsedMs" to (System.nanoTime() - started) / 1_000_000))
    }
    val ready = frame.ready && frame.session == state.currentId
    val timeline = if (ready) frame.items else emptyList()
    val activity = if (ready) frame.activity else ChatActivity()
    val codeScroll = remember(state.currentId) { HashMap<String, ScrollState>() }
    val enteredAt = remember(state.currentId) { System.currentTimeMillis() }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val atLatest by remember(list) { derivedStateOf { !list.canScrollForward } }
    fun pauseReading() { readingTools = true; followLatest = false; repository.retainVisibleHistory(state.currentId) }
    LaunchedEffect(dragged) { if (dragged) { readingTools = false; followLatest = false; repository.retainVisibleHistory(state.currentId) } }
    LaunchedEffect(atLatest, list.isScrollInProgress, positioned) {
        if (!list.isScrollInProgress && atLatest && !readingTools && positioned) followLatest = true
    }
    // Startup release never waits for layout/draw. The initial anchor is applied
    // below before measuring the first populated list, with its real footer size.
    SideEffect { if (ready) repository.firstFrame() }
    FollowChatEnd(list, ready && positioned && followLatest && !dragged && !readingTools) { followLatest && !readingTools && !dragged }
    suspend fun waitForExpand() {
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        delay(((Motion.Expand + 75) * scale).toLong() + 32L)
    }
    fun group(id: String) {
        pauseReading()
        groupJobs.remove(id)?.cancel()
        val children = timeline.filterIsInstance<OperationsHeader>().firstOrNull { it.group.id == id }?.group?.steps?.mapTo(HashSet()) { it.message.id }.orEmpty()
        val closing = id in openGroups
        val selection = OperationExpansion(openGroups, openSteps).group(id, children)
        openGroups = selection.groups; openSteps = selection.steps
        repository.logs.event("ui.operation.group", mapOf("group" to id, "expanded" to !closing, "index" to list.firstVisibleItemIndex, "offset" to list.firstVisibleItemScrollOffset))
        if (closing) {
            enteringGroups = enteringGroups - id
            closingGroups = closingGroups + id
            groupJobs[id] = scope.launch { waitForExpand(); closingGroups = closingGroups - id; groupJobs.remove(id) }
        } else {
            closingGroups = closingGroups - id
            enteringGroups = enteringGroups + id
            groupJobs[id] = scope.launch { waitForExpand(); enteringGroups = enteringGroups - id; groupJobs.remove(id) }
        }
    }
    fun step(id: String) {
        pauseReading(); openSteps = OperationExpansion(openGroups, openSteps).step(id).steps
        repository.logs.event("ui.operation.step", mapOf("step" to id, "expanded" to (id in openSteps), "index" to list.firstVisibleItemIndex, "offset" to list.firstVisibleItemScrollOffset))
    }
    fun retry(m: Message) { scope.launch { runCatching {
        repository.retryMessage(m.sessionId, m.id)
        readingTools = false; followLatest = true; repository.followRecentHistory(m.sessionId)
        repository.engine.send("", retry = true, sessionId = m.sessionId)
    }.onFailure { repository.notice(it.message) } } }
    var showLatest by remember(state.currentId) { mutableStateOf(false) }
    val currentTimeline by rememberUpdatedState(timeline)
    val hasOlder by rememberUpdatedState(state.historyMore[state.currentId] == true)
    val following by rememberUpdatedState(followLatest && !readingTools)
    LaunchedEffect(following) { if (following) { showLatest = false; repository.followRecentHistory(state.currentId) } }
    LaunchedEffect(list) {
        snapshotFlow {
            val index = list.firstVisibleItemIndex
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let { it.key.toString() to list.firstVisibleItemScrollOffset }
        }.collect { anchor -> if (anchor != null) { anchorKey = anchor.first; anchorOffset = anchor.second } }
    }
    val endTracker = remember(state.currentId) { ChatEndTracker() }
    LaunchedEffect(list, ready) {
        if (!ready) return@LaunchedEffect
        snapshotFlow { list.layoutInfo }.collect { info ->
            info.visibleItemsInfo.forEach { endTracker.record(it.key.toString(), it.size) }
            val last = info.visibleItemsInfo.lastOrNull()
            val viewport = (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding).coerceAtLeast(info.viewportSize.height / 2)
            val distance = if (last == null) 0f else endTracker.remaining(currentTimeline, hasOlder, last.index, last.offset + last.size,
                info.viewportEndOffset, info.afterContentPadding, viewport, info.visibleItemsInfo.size)
            showLatest = !following && LatestVisibility.show(distance, viewport, showLatest)
        }
    }
    val backdrop = LocalControlBackdrop.current as? LayerBackdrop
    val layoutEpoch = resumeEpoch
    val observedIme = WindowInsets.ime
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val measureStarted = System.nanoTime()
        var anchorRequested = false
        val loose = constraints.copy(minHeight = 0)
        val header = subcompose("header") {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton("menu", "打开会话", floating = true, onClick = onMenu)
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.floatingSurface(24.dp, compact = true).height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton("edit", "新会话", floating = false, onClick = onNew)
                        var bounds by remember { mutableStateOf(Rect.Zero) }
                        IconButton("more", "会话菜单", Modifier.onGloballyPositioned { bounds = it.boundsInRoot(); onPanelBounds("chat-menu", bounds) },
                            floating = false, onClick = { onPanel("chat-menu", bounds) })
                    }
                }
                val count = state.processes.count { it.sessionId == state.currentId && it.status == "running" }
                AnimatedVisibility(count > 0, Modifier.align(Alignment.End), enter = fadeIn(tween(220)) + expandVertically(expandFrom = Alignment.Top, clip = false),
                    exit = fadeOut(tween(180)) + shrinkVertically(shrinkTowards = Alignment.Top, clip = false)) {
                    var retained by remember { mutableIntStateOf(count) }
                    if (count > 0) SideEffect { retained = count }
                    var bounds by remember { mutableStateOf(Rect.Zero) }
                    Press(Modifier.padding(top = 10.dp).height(40.dp),
                        radius = 20.dp, description = "$retained 个运行中的任务", onClick = { onPanel("processes", bounds) }) {
                        Row(Modifier.floatingSurface(20.dp, compact = true).height(40.dp).onGloballyPositioned { bounds = it.boundsInRoot(); onPanelBounds("processes", bounds) }.padding(horizontal = 13.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(5.dp).background(Palette.Green, androidx.compose.foundation.shape.CircleShape))
                            Txt("$retained 个运行中", size = 12, weight = FontWeight.Medium)
                        }
                    }
                }
            }
        }.single().measure(loose)
        val footer = subcompose("footer") {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(if (imeEnabled) WindowInsets.ime else WindowInsets(0, 0, 0, 0))).padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                val status = activity.operation
                val pill = state.preferences.pill
                val pillHeight = maxOf(pill.height.dp, with(LocalDensity.current) { (pill.fontSize * LocalAppFontScale.current).sp.toDp() * 1.5f + 8.dp })
                Box(Modifier.fillMaxWidth().height(maxOf(46.dp, pillHeight + 14.dp)), contentAlignment = Alignment.Center) { StatusPill(status, status.isNotBlank(), state.preferences) }
                key(state.currentId) { Composer(repository, state, attachments, panelOpen, onPanel, onPanelBounds, onAttach, onRemoveAttachment, onImage) { text ->
                    val accepted = onSend(text)
                    if (accepted) { readingTools = false; followLatest = true; repository.followRecentHistory(state.currentId) }
                    accepted
                } }
            }
        }.single().measure(loose)
        if (ready && restoreAnchor) {
            if (!followLatest && anchorKey != null) {
                val index = if (anchorKey == "older") 0 else timeline.indexOfFirst { it.key == anchorKey }.takeIf { it >= 0 }?.plus(if (state.historyMore[state.currentId] == true) 1 else 0)
                if (index != null) { list.requestScrollToItem(index, anchorOffset); anchorRequested = true }
            }
            restoreAnchor = false
            repository.logs.event("ui.chat.entry", mapOf("session" to state.currentId, "following" to followLatest, "cached" to initialFrame.ready, "items" to timeline.size))
        }
        if (ready && viewportLayout.anchor(layoutEpoch, constraints.maxHeight, footer.height, followLatest && !dragged && !readingTools)) {
            val last = timeline.lastIndex + (if (state.historyMore[state.currentId] == true) 1 else 0) + (if (activity.thinking) 1 else 0)
            if (last >= 0) { list.requestScrollToItem(last, ChatViewportLayout.EndOffset); anchorRequested = true }
        }
        val top = header.height.toDp() + 8.dp
        val bottom = footer.height.toDp() + 10.dp
        val content = subcompose("timeline") {
            Box(Modifier.fillMaxSize().then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)) {
            ChatWallpaper(state.preferences.chatBackground)
            val light = wallpaperLightText(state.preferences.chatBackground)
            CompositionLocalProvider(LocalTranscriptInk provides (if (light) Color(0xFFF6F7F9) else Palette.Ink),
                LocalTranscriptSecondary provides (if (light) Color(0xFFCDD2DC) else Palette.Secondary)) {
            if (ready) LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top, bottom = bottom), verticalArrangement = Arrangement.Top) {
                if (state.historyMore[state.currentId] == true) item(key = "older") {
                    Press(Modifier.fillMaxWidth().height(48.dp), enabled = !loadingOlder, onClick = { pauseReading(); scope.launch {
                        loadingOlder = true
                        try { repository.loadOlder(state.currentId) } catch (e: CancellationException) { throw e } catch (e: Exception) { repository.notice(e.message) } finally { loadingOlder = false }
                    } }) { Txt(if (loadingOlder) "正在读取" else "更早的消息", size = 12, color = Palette.Secondary) }
                }
                items(timeline, key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is UserItem -> MessageArrival(item.message.id, item.message.created >= enteredAt, user = true, modifier = Modifier.padding(top = 20.dp)) {
                            UserMessage(item.message, onImage, state.currentId in state.tasks,
                                onContinue = { repository.engine.send("", retry = true, sessionId = item.message.sessionId) }) { actionMessage = item.message }
                        }
                        is ImageItem -> MessageArrival(item.key, item.message.created >= enteredAt, user = false) { ImageMessage(item.message, onImage) }
                        is TextPiece -> Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = blockSpacing(item.block, item.previous, item.index == 0))) {
                            SelectionContainer { MarkdownPiece(item.prepared, followLatest && !readingTools && !dragged && (item.message.status == "streaming" || item.message.created >= enteredAt), item.message.created >= enteredAt) }
                        }
                        is CodePiece -> Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = if (item.chunk == 0) 14.dp else 0.dp)) {
                            VirtualCodePiece(item, codeScroll.getOrPut(item.scrollKey) { ScrollState(0) }, followLatest && !readingTools && !dragged && (item.message.status == "streaming" || item.message.created >= enteredAt), item.message.created >= enteredAt)
                        }
                        is ReplyEnd -> ReplyFooter(item.message, item.latest, state.currentId in state.tasks, Modifier, onRetry = { retry(item.message) })
                        is OperationsHeader -> OperationsCard(item) { group(item.group.id) }
                        is OperationRow -> OperationRowView(item, item.group in enteringGroups, onClick = { step(item.step.message.id) }, onFull = { pauseReading(); onTool(item.step.message.id) })
                    }
                }
                if (activity.thinking) item(key = "assistant-waiting") {
                    ThinkingIndicator(label = when (state.phase) {
                        "正在思考" -> "思考中"
                        "正在准备操作" -> "准备操作"
                        "正在绘图" -> "绘图中"
                        else -> ""
                    }, startedAt = messages.lastOrNull { it.role == "assistant" && it.status == "streaming" }?.created ?: enteredAt)
                }
            } else Box(Modifier.fillMaxSize())
            }
            ScreenEdgeVeils(state.preferences.chatBackground)
            }
        }.single().measure(constraints)
        val latest = subcompose("latest") {
            LatestControl(showLatest) { scope.launch {
                readingTools = false; followLatest = false
                if (timeline.isNotEmpty()) list.animateScrollToItem(timeline.lastIndex + if (state.historyMore[state.currentId] == true) 1 else 0)
                repository.followRecentHistory(state.currentId); followLatest = true
            } }
        }.single().measure(loose.copy(minWidth = 0))
        Snapshot.withoutReadObservation {
            layoutTrace.measured(list, measureStarted, constraints.maxHeight, header.height, footer.height, observedIme.getBottom(this),
                anchorRequested, followLatest && !dragged, readingTools, ready)
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            content.place(0, 0); header.place(0, 0); footer.place(0, constraints.maxHeight - footer.height)
            latest.place(constraints.maxWidth - latest.width - 20.dp.roundToPx(), constraints.maxHeight - footer.height - latest.height - 8.dp.roundToPx())
        }
    }
    actionMessage?.let { m -> Sheet({ actionMessage = null }) { close ->
        Entry("copy", "复制") { clipboard.setText(AnnotatedString(m.text)); close() }
        if (m.status == "queued") Entry("close", "撤回插入") { close.after { scope.launch { repository.engine.withdrawInput(m) } } }
        Entry("edit", "修改并新建分支") { close.after { editing = m } }
    } }
    editing?.let { m ->
        var text by rememberSaveable(m.id) { mutableStateOf(m.text) }
        Sheet({ editing = null }) { close ->
            Field("", text, { text = it }, lines = 8)
            Spacer(Modifier.height(14.dp))
            ActionButton("在新分支中发送", Modifier.fillMaxWidth(), enabled = text.isNotBlank() && m.sessionId !in state.tasks) { close.after { scope.launch {
                runCatching {
                    val oldRoot = repository.workspace(m.sessionId).absolutePath
                    val branch = repository.fork(m.id, sessionId = m.sessionId)
                    val files = m.attachments.map { it.copy(path = it.path.replace(oldRoot, repository.workspace(branch.id).absolutePath)) }
                    repository.engine.send(text, files, sessionId = branch.id)
                }.onFailure { repository.notice(it.message) }
            } } }
        }
    }
}

@Composable private fun LatestControl(visible: Boolean, onClick: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var present by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) present = true
        progress.animateTo(if (visible) 1f else 0f, tween(210, easing = Motion.Travel))
        if (!visible) present = false
    }
    Box(Modifier.size(42.dp)) {
        if (present) Press(Modifier.size(42.dp).graphicsLayer {
            alpha = progress.value; translationY = (1 - progress.value) * 7.dp.toPx()
            compositingStrategy = CompositingStrategy.ModulateAlpha; clip = false
        }.floatingSurface(21.dp, compact = true), enabled = visible, radius = 21.dp, description = "回到最新消息", onClick = onClick) { Glyph("down", Modifier.size(20.dp)) }
    }
}

@Composable private fun ReplyFooter(message: Message, latest: Boolean, running: Boolean, modifier: Modifier, onRetry: () -> Unit) {
    var turns by remember(message.id) { mutableFloatStateOf(0f) }
    val rotation by animateFloatAsState(turns, tween(460, easing = Motion.Travel), label = "retryTurn")
    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        if (message.error.isNotBlank()) Txt(message.error, Modifier.padding(top = 10.dp), size = 13, color = Palette.Red)
        if (message.status in setOf("stopped", "interrupted", "paused")) Txt(when (message.status) { "stopped" -> "已停止"; "interrupted" -> "此段回复曾中断"; else -> "等待继续" }, Modifier.padding(top = 8.dp), size = 12, color = Palette.Secondary)
        if (message.status != "streaming") Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (message.text.isNotBlank()) CopyControl(message.text, "复制回复")
            if (latest) Press(Modifier.size(40.dp), enabled = !running, radius = 20.dp, description = if (message.status == "error") "在本会话重试" else "重新生成回复",
                onClick = { turns -= 360f; onRetry() }) { Glyph("refresh", Modifier.size(18.dp).graphicsLayer { rotationZ = rotation }, Palette.Secondary) }
        }
    }
}
