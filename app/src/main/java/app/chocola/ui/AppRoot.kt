package app.chocola.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.chocola.agent.AgentEngine
import app.chocola.agent.BackgroundAccess
import app.chocola.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

@Composable fun AppRoot(repository: Repository, sharedText: String?, openConversationId: String? = null, onConversationOpened: () -> Unit = {}, onShared: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chatStates = rememberSaveableStateHolder()
    val timelineCache = remember(repository) { ChatTimelineCache(repository.logs) }
    // Token updates belong to the conversation. They must not rebuild navigation or settings.
    val chrome = remember(repository, scope) { repository.state.project(scope) { it.copy(messages = emptyMap()) } }
    val state by chrome.collectAsStateWithLifecycle()
    val chatBackdrop = rememberLayerBackdrop()
    val plainBackdrop = rememberCanvasBackdrop { drawRect(Color.White) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val navigationTrace = remember(repository) { arrayOfNulls<TraceSpan>(1) }
    fun traceNavigation(target: String, direction: String) {
        navigationTrace[0]?.finish("superseded")
        navigationTrace[0] = repository.logs.begin("motion", fields = mapOf("category" to "navigation", "direction" to direction,
            "from" to navigation.currentDestination?.route?.substringBefore('?'), "to" to target.substringBefore(':').substringBefore('?'), "configuredMs" to Motion.Page))
    }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var chatViewportHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var panelAnchors by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    var panelSession by remember { mutableStateOf("") }
    var stoppingProcess by remember { mutableStateOf<SandboxProcess?>(null) }
    fun anchor(name: String, bounds: Rect) {
        if (bounds.width > 0 && bounds.height > 0 && panelAnchors[name] != bounds) panelAnchors = panelAnchors + (name to bounds)
    }
    LaunchedEffect(state.currentId) { sheet = null; panelSession = state.currentId }
    var mcpDraft by rememberSaveable { mutableStateOf(McpServer().json().toString()) }
    var providerDraft by rememberSaveable { mutableStateOf(Provider().json().toString()) }
    var vendorDraft by rememberSaveable { mutableStateOf(ApiVendor(baseUrl = "").json().toString()) }
    var memoryDraft by rememberSaveable { mutableStateOf(Memory().json().toString()) }
    var filePath by rememberSaveable { mutableStateOf("") }
    var imageDocument by rememberSaveable { mutableStateOf("{}") }
    var annotationSession by rememberSaveable { mutableStateOf("") }
    var toolMessage by rememberSaveable { mutableStateOf("") }
    var toolSession by rememberSaveable { mutableStateOf("") }
    var importTarget by rememberSaveable { mutableStateOf("") }
    val attachmentDrafts by repository.attachmentDrafts.collectAsStateWithLifecycle()
    val attachments = attachmentDrafts.mapValues { it.value.items }
    var requestBattery by rememberSaveable { mutableStateOf(false) }
    var batteryAfterNotification by rememberSaveable { mutableStateOf(false) }
    fun work(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { repository.logs.error("ui.operation.failed", e); repository.notice(Redactor.clean(e.message ?: "操作失败")) } } }
    fun leaveInput() { focus.clearFocus(force = true); keyboard?.hide() }
    fun go(route: String) {
        if (navigation.currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
        leaveInput()
        traceNavigation(route, "forward")
        navigation.navigate(if (route.startsWith("files:")) "files?path=${Uri.encode(route.substringAfter(':'))}" else route) { launchSingleTop = true }
    }
    fun back() {
        if (navigation.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
            leaveInput(); traceNavigation(navigation.previousBackStackEntry?.destination?.route.orEmpty(), "back")
            if (!navigation.popBackStack()) { navigationTrace[0]?.finish("no_destination"); navigationTrace[0] = null }
        }
    }
    fun provider(p: Provider?) {
        if (p == null) go("providers") else { providerDraft = p.json().toString(); go("provider") }
    }
    fun vendor(value: ApiVendor?) { vendorDraft = (value ?: ApiVendor(baseUrl = "")).json().toString(); go("vendor") }
    fun memory(m: Memory?) { memoryDraft = (m ?: Memory()).json().toString(); go("memory") }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (batteryAfterNotification) { batteryAfterNotification = false; requestBattery = true }
    }
    fun taskPermissions() {
        val permissions = context.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE)
        val askBattery = !permissions.getBoolean("batteryAsked", false) && !BackgroundAccess.batteryExempt(context)
        if (askBattery) permissions.edit().putBoolean("batteryAsked", true).apply()
        if (Build.VERSION.SDK_INT >= 33 && !permissions.getBoolean("notificationsAsked", false) && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.edit().putBoolean("notificationsAsked", true).apply()
            batteryAfterNotification = askBattery
            notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (askBattery) requestBattery = true
    }
    LaunchedEffect(requestBattery) {
        if (requestBattery) {
            delay(500)
            repository.logs.event("power.exemption_requested", BackgroundAccess.snapshot(context))
            runCatching { context.startActivity(BackgroundAccess.batteryIntent(context)) }.onFailure { repository.logs.error("power.request.failed", it) }
            requestBattery = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = importTarget
        val session = target.substringAfter(':').substringBefore(':')
        val asAttachment = target.startsWith("attach:")
        val relative = if (asAttachment) "imports" else target.substringAfter(':').substringAfter(':', "")
        work {
            val files = withContext(Dispatchers.IO) { uris.map { uri -> importFile(repository, uri, session, relative) } }
            if (asAttachment) repository.addDraftAttachments(session, files)
            else { repository.filesChanged(); repository.notice("已导入 ${files.size} 个文件") }
        }
    }
    fun import(session: String, relative: String = "", attach: Boolean = false) {
        importTarget = if (attach) "attach:$session" else "files:$session:$relative"
        picker.launch(arrayOf("*/*"))
    }
    fun share(file: File, mime: String = "text/plain") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }
    fun sharedStorage() {
        if (Build.VERSION.SDK_INT >= 30) context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
        else notification.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    DisposableEffect(lifecycle, focus) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) leaveInput() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(entry) {
        val target = entry
        if (target != null && target.lifecycle.currentState != Lifecycle.State.RESUMED && navigationTrace[0] == null) {
            navigationTrace[0] = repository.logs.begin("motion", fields = mapOf("category" to "navigation", "source" to "entry_lifecycle",
                "to" to target.destination.route?.substringBefore('?'), "configuredMs" to Motion.Page))
        }
        fun settled() {
            navigationTrace[0]?.finish(fields = mapOf("destination" to target?.destination?.route?.substringBefore('?')))
            navigationTrace[0] = null
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) settled() }
        target?.lifecycle?.addObserver(observer)
        if (target?.lifecycle?.currentState == Lifecycle.State.RESUMED) settled()
        else navigationTrace[0]?.stage("transition", mapOf("destination" to target?.destination?.route?.substringBefore('?')))
        onDispose { target?.lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(repository) { onDispose { navigationTrace[0]?.finish("root_disposed"); navigationTrace[0] = null } }
    SideEffect { repository.logs.currentSession = state.currentId }
    LaunchedEffect(entry?.id, drawer, sheet, state.approval?.id) {
        leaveInput()
        val page = when { state.approval != null -> "approval"; sheet != null -> "sheet:$sheet"; drawer -> "sessions"; else -> entry?.destination?.route?.substringBefore('?') ?: "chat" }
        repository.logs.screen = page
        repository.logs.event("ui.page", mapOf("page" to page))
    }
    LaunchedEffect(openConversationId, state.loaded) {
        if (openConversationId != null && state.loaded) {
            leaveInput(); drawer = false; sheet = null
            repository.select(openConversationId)
            navigation.popBackStack("chat", false)
            onConversationOpened()
        }
    }
    LaunchedEffect(sharedText, state.loaded) {
        if (sharedText != null && state.loaded && state.current != null) {
            repository.saveDraft(state.currentId, state.current!!.draft.let { if (it.isBlank()) sharedText else "$it\n$sharedText" })
            navigation.popBackStack("chat", false); onShared()
        }
    }
    LaunchedEffect(state.notice) { if (state.notice != null) { delay(5000); repository.notice(null) } }
    @Composable fun page(route: String) {
        when {
            route == "chat" -> chatStates.SaveableStateProvider(state.currentId) { ChatPage(repository, timelineCache, state, attachments[state.currentId].orEmpty(),
                onMenu = { leaveInput(); drawer = true },
                onNew = { leaveInput(); work { repository.createSession() } },
                imeEnabled = !drawer,
                panelOpen = sheet,
                onPanel = { name, bounds -> leaveInput(); panelSession = state.currentId; anchor(name, bounds); sheet = name },
                onPanelBounds = ::anchor,
                onAttach = { leaveInput(); import(state.currentId, attach = true) },
                onTool = { id -> toolMessage = id; toolSession = state.currentId; go("tool") },
                onImage = { image ->
                    imageDocument = image.json().toString()
                    if (image.draftId.isNotBlank()) { annotationSession = state.currentId; go("annotate") } else go("image")
                },
                onRemoveAttachment = { a -> val id = state.currentId; work { repository.removeDraftAttachments(id, listOf(a)) } },
                onSend = { text ->
                    if (state.currentProvider == null) { provider(null); false }
                    else {
                        val id = state.currentId
                        val submitted = attachments[id].orEmpty()
                        val rendered = submitted.map { image ->
                            if (image.draftId.isBlank() || !image.mime.startsWith("image/")) image else {
                                val history = repository.annotations.open(image.draftId).state.value
                                if (history.actions.isEmpty() && image.originalPath.isBlank()) image else AnnotationRenderer.export(image, history)
                            }
                        }
                        val accepted = repository.engine.submit(text, ImageReferences.submitted(rendered), sessionId = id)
                        if (accepted) { repository.removeDraftAttachments(id, submitted); taskPermissions() }
                        accepted
                    }
                }) }
            route == "setup" -> FirstSetupPage(state, repository, onApi = { go("providers") }, onRestore = { go("data") },
                onNotifications = {
                    if (Build.VERSION.SDK_INT >= 33 && !BackgroundAccess.notificationsEnabled(context)) {
                        context.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE).edit().putBoolean("notificationsAsked", true).apply()
                        notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else runCatching { context.startActivity(BackgroundAccess.notificationIntent(context)) }
                }, onBackground = {
                    context.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).apply()
                    runCatching { context.startActivity(BackgroundAccess.batteryIntent(context)) }.onFailure { context.startActivity(BackgroundAccess.appSettingsIntent(context)) }
                })
            route == "display" || route == "font" -> DisplayPage(state, repository, ::back, initialFont = route == "font") { go(it) }
            route == "skills" -> SkillsPage(state, repository, ::back)
            route == "mcp" -> McpPage(state, repository, ::back) { server -> mcpDraft = server.json().toString(); go("mcp-editor") }
            route == "mcp-editor" -> McpEditor(McpServer.from(org.json.JSONObject(mcpDraft)), repository, ::back)
            route == "settings" -> SettingsPage(state, ::back) { go(it) }
            route == "notebooks" -> NotebooksPage(state, repository, ::back) { path -> go("notebook?path=${Uri.encode(path)}") }
            route.startsWith("notebook:") -> NotebookPage(route.substringAfter(':'), state, repository, ::back) { file -> share(file, "text/markdown") }
            route == "providers" -> VendorsPage(state, repository, ::back) { value -> if (value?.id == CodexWire.VENDOR) go("codex") else vendor(value) }
            route == "codex" -> CodexPage(state, repository, ::back) { provider(it) }
            route == "vendor" -> VendorPage(ApiVendor.from(org.json.JSONObject(vendorDraft)), state, repository, ::back) { provider(it) }
            route == "provider" -> ProviderEditor(Provider.from(org.json.JSONObject(providerDraft)), repository, ::back) { p -> work { repository.saveProvider(p); back() } }
            route == "memories" -> MemoriesPage(state, repository, ::back, ::memory)
            route == "memory" -> MemoryEditor(Memory.from(org.json.JSONObject(memoryDraft)), state, repository, ::back) { m -> work { repository.saveMemory(m); back() } }
            route == "style" -> StylePage(state, repository, ::back)
            route == "image-settings" -> ImageSettingsPage(state, repository, ::back)
            route == "image" -> ImagePage(Attachment.from(org.json.JSONObject(imageDocument)), repository, ::back) { image -> share(File(image.path), image.mime) }
            route == "annotate" -> {
                val initial = Attachment.from(org.json.JSONObject(imageDocument))
                val image = attachmentDrafts[annotationSession]?.items?.firstOrNull { it.draftId == initial.draftId } ?: initial
                AnnotationPage(image, repository, onDone = { edited -> work { repository.updateDraftAttachment(annotationSession, image.draftId, edited); back() } })
            }
            route == "permissions" -> PermissionsPage(state, repository, ::back, ::sharedStorage) { go("tool-rules") }
            route == "tool-rules" -> ToolRulesPage(state, repository, ::back)
            route == "status-style" -> StatusAppearancePage(state, repository, ::back)
            route == "floating-style" -> FloatingControlsPage(state, repository, ::back)
            route == "chat-background" -> ChatBackgroundPage(state, repository, ::back)
            route == "context" -> ContextPage(repository, state, ::back) { state.currentProvider?.let(::provider) }
            route == "tool" -> ToolDetailPage(repository, toolSession, toolMessage, ::back)
            route == "background" -> BackgroundPage(repository, ::back)
            route == "data" -> DataPage(repository, state, ::back)
            route.startsWith("files:") -> FilesPage(repository, state, route.substringAfter(':'), ::back,
                onDirectory = { relative -> go("files:$relative") }, onFile = { path -> filePath = path; go("file") },
                onImport = { import(state.currentId, route.substringAfter(':')) },
                onShare = { file -> share(file, "application/octet-stream") })
            route == "file" -> FilePage(repository, state.currentId, filePath, ::back) { file -> share(file, "application/octet-stream") }
        }
    }
    LaunchedEffect(state.preferences.setupCompleted, entry?.destination?.route) {
        if (state.loaded && state.preferences.setupCompleted && entry?.destination?.route == "setup") {
            navigation.navigate("chat") { popUpTo(navigation.graph.id) { inclusive = true }; launchSingleTop = true }
        }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    val lightBars = entry?.destination?.route == "chat" && !drawer && wallpaperLightText(state.preferences.chatBackground)
    SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !lightBars
                isAppearanceLightNavigationBars = !lightBars
            }
        }
    }
    SideEffect { if (state.loaded && entry?.destination?.route?.let { it != "chat" } == true) repository.firstFrame() }
    val currentPage by rememberUpdatedState<@Composable (String) -> Unit>({ page(it) })
    ChocolaTheme(state.preferences.fontScale) {
        CompositionLocalProvider(LocalDiagnostics provides repository.logs, LocalFloatingDesign provides state.preferences.floating,
            LocalControlBackdrop provides (if (entry?.destination?.route == "chat" && state.preferences.floating.style == "glass" && Build.VERSION.SDK_INT >= 31) chatBackdrop else plainBackdrop)) {
        Box(Modifier.fillMaxSize().background(Palette.Canvas).clipToBounds()) {
            if (state.loaded) {
                NavHost(navigation, startDestination = remember { if (state.preferences.setupCompleted) "chat" else "setup" }, modifier = Modifier.fillMaxSize(),
                    enterTransition = { slideInHorizontally(tween(Motion.Page, easing = Motion.Travel)) { it } },
                    exitTransition = { slideOutHorizontally(tween(Motion.Page, easing = Motion.Travel)) { -it / 4 } },
                    popEnterTransition = { slideInHorizontally(tween(Motion.Page, easing = Motion.Travel)) { -it / 4 } },
                    popExitTransition = { slideOutHorizontally(tween(Motion.Page, easing = Motion.Travel)) { it } },
                    sizeTransform = null) {
                    listOf("chat", "setup", "display", "font", "skills", "mcp", "mcp-editor", "settings", "notebooks", "codex", "providers", "vendor", "provider", "memories", "memory", "style", "image-settings", "image", "annotate", "permissions", "tool-rules", "status-style", "floating-style", "chat-background", "context", "tool", "background", "data", "file").forEach { route ->
                        composable(route) {
                            Box(Modifier.fillMaxSize()
                                .then(if (route == "chat" && drawer && chatViewportHeight > 0) Modifier.wrapContentHeight(Alignment.Top, unbounded = true).requiredHeight(with(density) { chatViewportHeight.toDp() }) else Modifier)
                                .onSizeChanged { if (route == "chat" && !drawer) chatViewportHeight = it.height }
                                .background(if (route == "chat") Palette.Canvas else Color.White)) {
                                currentPage(route)
                            }
                        }
                    }
                    composable("notebook?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) { target ->
                        Box(Modifier.fillMaxSize().background(Color.White)) { currentPage("notebook:${target.arguments?.getString("path").orEmpty()}") }
                    }
                    composable("files?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) { target ->
                        Box(Modifier.fillMaxSize().background(Color.White)) { currentPage("files:${target.arguments?.getString("path").orEmpty()}") }
                    }
                }
            }
            if (state.transfer.isNotBlank()) {
                Press(Modifier.fillMaxSize().background(Color.White.copy(alpha = .96f)), description = state.transfer, onClick = {}) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ReplyActivity(); Txt(state.transfer, size = 14)
                    }
                }
                androidx.activity.compose.BackHandler { }
            }
            state.notice?.let { notice ->
                Press(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 62.dp, start = 20.dp, end = 20.dp).background(Palette.Ink, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)), onClick = { repository.notice(null) }) {
                    Txt(notice, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), size = 14, color = Color.White, maxLines = 4)
                }
            }
            if (drawer) SessionsDrawer(state, repository, onDismiss = { drawer = false }, onNew = { work { repository.createSession() } }, onNavigate = { go(it) }, onSelected = { id ->
                repository.select(id)
                val selected = repository.state.value
                withContext(Dispatchers.Default) { timelineCache.warm(id, selected.messages[id].orEmpty(), id in selected.tasks) }
                navigation.popBackStack("chat", false)
            })
            if (panelSession == state.currentId) {
                if (sheet == "access") panelAnchors["access"]?.let { bounds ->
                    AccessMenu(bounds, state.current?.access ?: Access.ASK, { sheet = null }) { access ->
                        val id = panelSession
                        work { repository.updateSession(id) { it.copy(access = access) } }
                    }
                }
                if (sheet == "thinking") panelAnchors["thinking"]?.let { bounds ->
                    val p = state.providers.firstOrNull { it.id == state.current?.providerId }
                    if (p != null) ThinkingMenu(bounds, p, state.current, state.currentId in state.tasks, state.providers,
                        onDismiss = { sheet = null }, onModel = { model -> work { repository.updateSession(panelSession) { it.copy(providerId = model.id) } } }) { level ->
                        val id = panelSession
                        work { repository.chooseReasoning(id, p, level) }
                    }
                }
                if (sheet == "chat-menu") panelAnchors["chat-menu"]?.let { bounds ->
                    AnchoredPanel(bounds, "当前会话", 288, 350, PanelGrowth.MENU, { sheet = null }) { close ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                            Txt(state.current?.title ?: "当前会话", Modifier.padding(horizontal = 12.dp, vertical = 10.dp), size = 12, color = Palette.Secondary, maxLines = 1)
                            Box(Modifier.onGloballyPositioned { anchor("models", it.boundsInRoot()) }) {
                                Entry("sliders", "当前模型", state.currentProvider?.model ?: "未选择") { sheet = "models" }
                            }
                            Entry("folder", "会话文件") { close.after { go("files:") } }
                            Entry("context", "上下文") { close.after { go("context") } }
                            Entry("pin", if (state.current?.pinned == true) "取消置顶" else "置顶会话") {
                                val id = panelSession
                                close.after { work { repository.updateSession(id) { it.copy(pinned = !it.pinned) } } }
                            }
                            Entry("share", "导出会话") {
                                val id = panelSession
                                close.after { work { share(repository.exportMarkdown(id), "text/markdown") } }
                            }
                        }
                    }
                }
                if (sheet == "models") (panelAnchors["models"] ?: panelAnchors["thinking"] ?: panelAnchors["chat-menu"])?.let { bounds ->
                    ModelPickerPanel(bounds, state.providers, state.current?.providerId.orEmpty(), { sheet = null }, onSelect = { model ->
                        sheet = null; work { repository.updateSession(panelSession) { it.copy(providerId = model.id) } }
                    }, onManage = { sheet = null; go("providers") })
                }
                if (sheet == "processes") panelAnchors["processes"]?.let { bounds ->
                    val processes = state.processes.filter { it.sessionId == state.currentId && it.status == "running" }
                    AnchoredPanel(bounds, "运行中的任务", 318, (62 + processes.size.coerceIn(1, 5) * 70), PanelGrowth.TASKS, { sheet = null }) { close ->
                        Column(Modifier.fillMaxSize().padding(10.dp)) {
                            Row(Modifier.fillMaxWidth().height(42.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Txt("运行中的任务", Modifier.weight(1f), size = 14, weight = FontWeight.Medium)
                                Txt(processes.size.toString(), Modifier.padding(end = 10.dp), size = 12, color = Palette.Secondary)
                            }
                            LazyColumn(Modifier.weight(1f)) {
                                items(processes, key = { it.id }) { process ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Txt(process.name.ifBlank { "后台程序" }, size = 14, maxLines = 2)
                                            Txt("运行中", size = 11, color = Palette.Green)
                                        }
                                        IconButton("close", "停止 ${process.name}") { close.after { stoppingProcess = process } }
                                    }
                                }
                                if (processes.isEmpty()) item { Txt("任务已结束", Modifier.padding(12.dp), size = 13, color = Palette.Secondary) }
                            }
                        }
                    }
                }
            }
        }
        stoppingProcess?.let { process ->
            ConfirmSheet("停止“${process.name.ifBlank { "后台程序" }}”？", "停止任务", { stoppingProcess = null }) {
                work {
                    if (!repository.runtime.stopBackground(process.sessionId, process.id)) repository.notice("该任务已结束")
                }
            }
        }
        state.scheduleAuthorizations.firstOrNull()?.let { request -> key(request.id) {
            ScheduleGrantSheet(request, state, repository,
                onNotifications = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else runCatching { context.startActivity(BackgroundAccess.notificationIntent(context)) }
                }, onAlarm = { runCatching { requestScheduleAlarm(context) } },
                onPower = { runCatching { context.startActivity(BackgroundAccess.batteryIntent(context)) }
                    .onFailure { context.startActivity(BackgroundAccess.appSettingsIntent(context)) } })
        } }
        state.approval?.let { request ->
            key(request.id) {
            var answer by remember(request.id) { mutableStateOf("deny") }
            Sheet(onDismiss = { repository.engine.resolveApproval(request.id, answer) }) { close ->
                Txt(request.call.displayName.ifBlank { AgentEngine.toolLabel(request.call.name) }, Modifier.padding(vertical = 8.dp), weight = FontWeight.Medium)
                if (request.sessionId != state.currentId) Txt(state.sessions.firstOrNull { it.id == request.sessionId }?.title.orEmpty(), Modifier.padding(bottom = 10.dp), size = 13, color = Palette.Secondary)
                Column(Modifier.heightIn(max = 300.dp).padding(bottom = 16.dp)) {
                    CodeBlock(runCatching { org.json.JSONObject(request.detail).toString(2) }.getOrDefault(request.detail), "", maxLines = 14)
                }
                ActionButton("允许本次", Modifier.fillMaxWidth()) { answer = "once"; close() }
                if (state.preferences.toolRules[request.call.name] != "ask") ActionButton("本会话允许同类操作", Modifier.fillMaxWidth(), solid = false) { answer = "session"; close() }
                Press(Modifier.fillMaxWidth().height(48.dp), onClick = { answer = "deny"; close() }) { Txt("拒绝", size = 14, color = Palette.Secondary) }
            }
            }
        }
    }
    }
}

private fun importFile(repository: Repository, uri: Uri, session: String, relative: String): Attachment {
    val resolver = repository.context.contentResolver
    var name = "文件"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
    }
    require(size <= 20_000_000) { "单个文件不能超过 20 MB" }
    val directory = app.chocola.agent.WorkspacePaths(repository.workspace(session)).resolve(relative).apply { mkdirs() }
    val safeName = name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(180).ifBlank { "文件" }
    val output = File(directory, safeName).let { if (it.exists()) File(directory, "${newId().take(8)}-$safeName") else it }
    try {
        requireNotNull(resolver.openInputStream(uri)).use { input -> output.outputStream().use { out ->
            val bytes = ByteArray(16384); var total = 0
            while (true) { val count = input.read(bytes); if (count < 0) break; total += count; require(total <= 20_000_000) { "文件超过 20 MB" }; out.write(bytes, 0, count) }
        } }
    } catch (e: Exception) { output.delete(); throw e }
    repository.logs.event("file.import", mapOf("session" to session, "name" to safeName, "bytes" to output.length()))
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val dimensions = if (mime.startsWith("image/")) runCatching { ImageOrientation.dimensions(output, ImageDimensions.read(output, mime)) }.getOrDefault(0 to 0) else 0 to 0
    return Attachment(output.name, output.absolutePath, mime, output.length(), dimensions.first, dimensions.second)
}

@Composable fun AccessPicker(selected: Access, onDismiss: () -> Unit, onSelect: (Access) -> Unit) {
    Sheet(onDismiss) { close ->
        LazyColumn(Modifier.heightIn(max = 430.dp)) { items(Access.entries, key = { it.name }) { access ->
            Press(Modifier.fillMaxWidth().heightIn(min = 76.dp), onClick = { close.after { onSelect(access) } }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Txt(access.label, weight = FontWeight.Medium)
                        Txt(accessDescription(access), size = 12, color = Palette.Secondary)
                    }
                    if (access == selected) Glyph("check", Modifier.size(20.dp))
                }
            }
        } }
    }
}
