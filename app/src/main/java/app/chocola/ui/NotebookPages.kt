package app.chocola.ui

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chocola.data.*
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun NotebooksPage(state: AppState, repository: Repository, onBack: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var folder by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<NotebookItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<NotebookItem?>(null) }
    var rename by remember { mutableStateOf<NotebookItem?>(null) }
    var move by remember { mutableStateOf<NotebookItem?>(null) }
    var deleting by remember { mutableStateOf<NotebookItem?>(null) }
    var trash by remember { mutableStateOf(false) }
    fun work(block: suspend () -> Unit) { scope.launch { try { withContext(Dispatchers.IO) { block() } } catch (e: CancellationException) { throw e } catch (e: Exception) { repository.notice(e.message) } } }
    fun back() { if (folder.isBlank()) onBack() else { folder = folder.substringBeforeLast('/', ""); query = "" } }
    BackHandler(folder.isNotBlank()) { back() }
    LaunchedEffect(folder, query, state.notebooksVersion) {
        if (query.isNotBlank()) delay(180)
        try { entries = withContext(Dispatchers.IO) { repository.notebooks.list(folder, query) }; loaded = true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (folder.isNotBlank()) folder = ""; repository.notice(e.message) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> work {
        uris.forEach { uri ->
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "笔记.md"
            val bytes = context.contentResolver.openInputStream(uri)?.use { source -> val output = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192); while (output.size() <= NotebookStore.MAX_BYTES) { val count = source.read(chunk, 0, minOf(chunk.size, NotebookStore.MAX_BYTES + 1 - output.size())); if (count < 0) break; output.write(chunk, 0, count) }; output.toByteArray() } ?: error("无法读取文件")
            require(bytes.size <= NotebookStore.MAX_BYTES) { "笔记不能超过 2 MB" }
            repository.notebooks.create(folder, name, false, bytes.toString(Charsets.UTF_8))
        }
    } }
    SettingsScaffold(::back, actions = {
        IconButton("more", "笔记本选项") { menu = true }
        IconButton("plus", "创建笔记或文件夹") { create = true }
    }, header = { Field("", query, { query = it }, placeholder = "搜索笔记") }) {
        item("path") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Press(Modifier.height(36.dp).padding(horizontal = 10.dp), radius = 10.dp, onClick = { folder = ""; query = "" }) { Txt("笔记本", size = 13, weight = FontWeight.Medium) }
                folder.split('/').filter(String::isNotBlank).forEachIndexed { index, name ->
                    Glyph("right", Modifier.size(10.dp), Palette.Secondary)
                    Press(Modifier.height(36.dp).padding(horizontal = 10.dp), radius = 10.dp, onClick = { folder = folder.split('/').take(index + 1).joinToString("/"); query = "" }) { Txt(name, size = 13, maxLines = 1) }
                }
            }
        }
        items(entries, key = { it.path }) { entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Press(Modifier.weight(1f).heightIn(min = 72.dp), radius = 15.dp, onClick = { if (entry.directory) { folder = entry.path; query = "" } else open(entry.path) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(Palette.Fill, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Glyph(if (entry.directory) "folder" else "notebook", Modifier.size(21.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Txt(if (entry.directory) entry.name else entry.name.removeSuffix(".md"), size = 14, weight = FontWeight.Medium, maxLines = 2)
                            Txt(if (query.isNotBlank()) entry.path else if (entry.directory) "文件夹" else noteTime(entry.modified), size = 11, color = Palette.Secondary, maxLines = 1)
                        }
                    }
                }
                IconButton("more", "${entry.name} 的操作", Modifier.size(42.dp)) { chosen = entry }
            }
        }
        if (loaded && entries.isEmpty()) item("empty") {
            if (query.isBlank()) Entry("plus", "创建笔记", onClick = { create = true })
            else Txt("没有匹配的笔记", Modifier.padding(12.dp), size = 13, color = Palette.Secondary)
        }
    }
    if (menu) Sheet({ menu = false }) { close ->
        Entry("upload", "导入 Markdown") { close.after { importer.launch(arrayOf("text/markdown", "text/plain", "text/x-markdown")) } }
        Entry("trash", "最近删除") { close.after { trash = true } }
    }
    if (create) {
        var name by rememberSaveable { mutableStateOf("") }
        var kind by rememberSaveable { mutableIntStateOf(0) }
        Sheet({ create = false }) { close ->
            Segments(listOf("笔记", "文件夹"), kind, { kind = it })
            Spacer(Modifier.height(18.dp)); Field("", name, { name = it }, placeholder = if (kind == 0) "笔记名称" else "文件夹名称")
            Spacer(Modifier.height(18.dp))
            ActionButton("创建", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) { work {
                val entry = repository.notebooks.create(folder, name, kind == 1)
                withContext(Dispatchers.Main) { close.after { if (!entry.directory) open(entry.path) } }
            } }
        }
    }
    chosen?.let { entry -> Sheet({ chosen = null }) { close ->
        Txt(entry.name, Modifier.padding(12.dp), size = 13, color = Palette.Secondary, maxLines = 2)
        Entry("edit", "重命名") { close.after { rename = entry } }
        Entry("folder", "移动到") { close.after { move = entry } }
        Entry("trash", "移到最近删除", danger = true) { close.after { deleting = entry } }
    } }
    rename?.let { entry ->
        var name by rememberSaveable { mutableStateOf(if (entry.directory) entry.name else entry.name.removeSuffix(".md")) }
        Sheet({ rename = null }) { close ->
            Field("", name, { name = it }, placeholder = "名称"); Spacer(Modifier.height(18.dp))
            ActionButton("保存", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) { work {
                repository.notebooks.move(entry.path, entry.path.substringBeforeLast('/', ""), name)
                withContext(Dispatchers.Main) { close() }
            } }
        }
    }
    move?.let { entry -> NotebookMoveSheet(entry, repository, { move = null }) { parent -> work {
        repository.notebooks.move(entry.path, parent, entry.name); withContext(Dispatchers.Main) { move = null }
    } } }
    deleting?.let { entry -> ConfirmSheet("移走“${entry.name}”？", "移到最近删除", { deleting = null }) { work { repository.notebooks.trash(entry.path) } } }
    if (trash) NotebookTrashSheet(state, repository) { trash = false }
}

@Composable private fun NotebookMoveSheet(entry: NotebookItem, repository: Repository, dismiss: () -> Unit, move: (String) -> Unit) {
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(entry.path.substringBeforeLast('/', "")) }
    LaunchedEffect(Unit) { folders = withContext(Dispatchers.IO) { repository.notebooks.folders().filter { it != entry.path && !it.startsWith(entry.path + "/") }.sorted() } }
    Sheet(dismiss) { close ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(listOf("") + folders, key = { it }) { path ->
                Entry(if (path == selected) "check" else "folder", path.ifBlank { "笔记本" }) { selected = path }
            }
        }
        Spacer(Modifier.height(12.dp)); ActionButton("移动到此处", Modifier.fillMaxWidth()) { close.after { move(selected) } }
    }
}

@Composable private fun NotebookTrashSheet(state: AppState, repository: Repository, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<NotebookTrash>>(emptyList()) }
    LaunchedEffect(state.notebooksVersion) { entries = withContext(Dispatchers.IO) { repository.notebooks.trashList() } }
    Sheet(dismiss) { _ ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
            items(entries, key = { it.id }) { entry -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Txt(entry.path, size = 14, maxLines = 2); Txt(noteTime(entry.time), size = 11, color = Palette.Secondary) }
                IconButton("undo", "恢复笔记") { scope.launch { try { withContext(Dispatchers.IO) { repository.notebooks.restoreTrash(entry.id) } } catch (e: Exception) { repository.notice(e.message) } } }
            } }
            if (entries.isEmpty()) item { Txt("没有已删除的笔记", Modifier.padding(18.dp), size = 13, color = Palette.Secondary) }
        }
    }
}

@Composable internal fun NotebookPage(path: String, state: AppState, repository: Repository, onBack: () -> Unit, share: (File) -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var document by remember(path) { mutableStateOf<NotebookDocument?>(null) }
    var buffer by remember(path) { mutableStateOf<NotebookEditing?>(null) }
    var recoveredDraft by remember(path) { mutableStateOf<NotebookDraft?>(null) }
    val backdrop = rememberLayerBackdrop()
    var value by rememberSaveable(path, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var editing by rememberSaveable(path) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var heading by remember { mutableStateOf(false) }
    val undo = remember(path) { mutableStateListOf<TextFieldValue>() }
    val redo = remember(path) { mutableStateListOf<TextFieldValue>() }
    var undoAt by remember { mutableLongStateOf(0L) }
    fun edit(next: TextFieldValue, command: Boolean = false) {
        if (next.text != value.text) {
            val now = android.os.SystemClock.uptimeMillis()
            if (command || now - undoAt > 700 || undo.isEmpty()) { undo += value; undoAt = now }
            while (undo.size > 40 || undo.sumOf { it.text.length.toLong() } > 8_000_000) undo.removeAt(0)
            redo.clear(); buffer?.changed(next.text)
        }
        value = next
    }
    fun transform(change: TextEdit) { edit(TextFieldValue(change.text, TextRange(change.start, change.end)), command = true) }
    fun toolbar(action: String) {
        if (action == "标题") { heading = true; return }
                    val s = value.selection.start; val e = value.selection.end; val text = value.text
                    transform(when (action) {
                        "标题" -> MarkdownEditing.line(text, s, e, "## ")
                        "粗体" -> MarkdownEditing.wrap(text, s, e, "**")
                        "斜体" -> MarkdownEditing.wrap(text, s, e, "*")
                        "删除线" -> MarkdownEditing.wrap(text, s, e, "~~")
                        "引用" -> MarkdownEditing.line(text, s, e, "> ")
                        "列表" -> MarkdownEditing.line(text, s, e, "- ")
                        "编号" -> MarkdownEditing.line(text, s, e, "1. ")
                        "待办" -> MarkdownEditing.line(text, s, e, "- [ ] ")
                        "代码" -> MarkdownEditing.wrap(text, s, e, "`", placeholder = "代码")
                        "代码块" -> MarkdownEditing.wrap(text, s, e, "\n```\n", "\n```\n", "代码")
                        "链接" -> MarkdownEditing.wrap(text, s, e, "[", "](https://)", "链接文字")
                        "公式" -> MarkdownEditing.wrap(text, s, e, "$", placeholder = "x^2")
                        "块公式" -> MarkdownEditing.wrap(text, s, e, "\n$$\n", "\n$$\n", "\\frac{a}{b}")
                        "表格" -> MarkdownEditing.insert(text, s, e, "\n| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |\n")
                        else -> MarkdownEditing.insert(text, s, e, "\n\n---\n\n")
                    })
                }
    fun beginEditing() {
        val current = document ?: return
        val draft = recoveredDraft
        buffer = NotebookEditing(repository.notebooks, repository.scope, current.copy(revision = draft?.revision ?: current.revision))
        value = TextFieldValue(draft?.text ?: current.text)
        if (draft != null) buffer?.changed(draft.text)
        recoveredDraft = null; undo.clear(); redo.clear(); undoAt = 0; editing = true
    }
    suspend fun finish(back: Boolean = false) {
        focus.clearFocus(); keyboard?.hide()
        try {
            if (buffer?.flush() != false) {
                document = withContext(Dispatchers.IO) { repository.notebooks.read(path) }
                editing = false; if (back) onBack()
            } else conflict = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { repository.notice(e.message); conflict = true }
    }
    fun back() { if (editing) scope.launch { finish() } else onBack() }
    BackHandler(editing) { back() }
    LaunchedEffect(path, state.notebooksVersion) {
        if (!editing || buffer == null) {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.notebooks.read(path) }
                document = loaded
                if (buffer == null) {
                    val draft = withContext(Dispatchers.IO) { repository.notebooks.draft(path) }
                    recoveredDraft = draft
                    buffer = NotebookEditing(repository.notebooks, repository.scope, loaded)
                    if (editing && draft != null) { buffer?.adopt(loaded.copy(revision = draft.revision)); value = TextFieldValue(draft.text); buffer?.changed(draft.text) }
                    else value = TextFieldValue(loaded.text)
                } else if (!editing) { buffer?.adopt(loaded); value = TextFieldValue(loaded.text) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { repository.notice(e.message) }
        }
    }
    DisposableEffect(buffer) { onDispose { buffer?.persist() } }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, buffer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) buffer?.persist() }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val save = buffer?.state?.collectAsState()?.value ?: NotebookSaveState()
    CompositionLocalProvider(LocalControlBackdrop provides backdrop) {
    SettingsLayout(::back, compactTop = true, footer = if (editing) { { Box(Modifier.imePadding()) { NotebookToolbar(::toolbar) } } } else null, actions = {
        if (editing) {
            IconButton("undo", "撤销", enabled = undo.isNotEmpty()) { if (undo.isNotEmpty()) { redo += value; value = undo.removeAt(undo.lastIndex); buffer?.changed(value.text) } }
            IconButton("redo", "重做", enabled = redo.isNotEmpty()) { if (redo.isNotEmpty()) { undo += value; value = redo.removeAt(redo.lastIndex); buffer?.changed(value.text) } }
            IconButton(if (save.conflict) "info" else "check", "保存并阅读", enabled = document != null) { scope.launch { finish() } }
        } else {
            IconButton("more", "笔记选项", enabled = document != null) { menu = true }
            IconButton("edit", if (recoveredDraft != null) "恢复草稿并编辑" else "编辑笔记", enabled = document != null) { beginEditing() }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().layerBackdrop(backdrop).background(androidx.compose.ui.graphics.Color.White)) {
            if (editing) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(padding).padding(bottom = 64.dp)) {
                    if (save.error.isNotBlank()) Press(Modifier.fillMaxWidth().padding(bottom = 14.dp), onClick = { conflict = true }) { Txt(save.error, size = 12, color = Palette.Red) }
                    BasicTextField(value, { edit(it) }, Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(horizontal = 6.dp, vertical = 10.dp),
                        textStyle = TextStyle(color = Palette.Ink, fontSize = (16 * LocalAppFontScale.current).sp, lineHeight = (27 * LocalAppFontScale.current).sp), cursorBrush = SolidColor(Palette.Ink),
                        decorationBox = { inner -> Box { if (value.text.isBlank()) Txt("开始记录", color = Palette.Secondary, size = 16); inner() } })
                }

            } else {
                NotebookReader(path, document?.text, padding, Modifier.weight(1f))
            }
        }
    }
    }
    if (menu) Sheet({ menu = false }) { close ->
        Entry("share", "分享 Markdown") { close.after { scope.launch { try { share(withContext(Dispatchers.IO) { repository.notebooks.export(path, repository.context.cacheDir) }) } catch (e: Exception) { repository.notice(e.message) } } } }
        Entry("clock", "历史版本") { close.after { history = true } }
    }
    if (heading) Sheet({ heading = false }) { close ->
        for (level in 0..6) Entry(text = if (level == 0) "正文" else "$level 级标题") { close.after { transform(MarkdownEditing.heading(value.text, value.selection.start, value.selection.end, level)) } }
    }
    if (history) NotebookHistorySheet(path, repository, { history = false }) { text ->
        value = TextFieldValue(text); buffer?.changed(text); editing = true; history = false
    }
    if (conflict) Sheet({ conflict = false }) { close ->
        Txt(save.error.ifBlank { "这份笔记已在别处修改" }, Modifier.padding(12.dp), size = 14)
        Entry("plus", "将草稿保存为新笔记") { scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.notebooks.create(path.substringBeforeLast('/', ""), path.substringAfterLast('/').removeSuffix(".md") + " 副本 " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss")), false, value.text)
                    repository.notebooks.clearDraft(path)
                }
                val current = withContext(Dispatchers.IO) { repository.notebooks.read(path) }
                buffer?.adopt(current); value = TextFieldValue(current.text); document = current; editing = false; close()
            } catch (e: Exception) { repository.notice(e.message) }
        } }
        Entry("refresh", "保留草稿并返回") { close.after { focus.clearFocus(); keyboard?.hide(); onBack() } }
    }
}

@Composable private fun NotebookReader(path: String, text: String?, padding: PaddingValues, modifier: Modifier) {
    val prepared by produceState<List<PreparedBlock>>(emptyList(), text) {
        value = withContext(Dispatchers.Default) { StreamingMarkdown().update(text.orEmpty(), false).map { RichDocuments.prepare(it, false) } }
    }
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item("name") { Txt(path.substringAfterLast('/').removeSuffix(".md"), Modifier.padding(horizontal = 6.dp, vertical = 12.dp), size = 21, weight = FontWeight.SemiBold) }
            if (text != null && text.isBlank()) item("blank") { Txt("空白笔记", Modifier.padding(6.dp), size = 14, color = Palette.Secondary) }
            itemsIndexed(prepared, key = { index, _ -> "block:$index" }) { index, block ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(top = blockSpacing(block.block, prepared.getOrNull(index - 1)?.block, false))) { MarkdownPiece(block) }
            }
        }
    }
}

@Composable private fun NotebookToolbar(choose: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
    Row(Modifier.fillMaxWidth().floatingSurface(100.dp, compact = true).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("标题", "粗体", "斜体", "列表", "待办", "引用", "编号", "代码", "代码块", "表格", "链接", "公式", "块公式", "删除线", "分隔线").forEach { label ->
            Press(Modifier.height(38.dp).padding(horizontal = 11.dp), radius = 11.dp, description = label, onClick = { choose(label) }) {
                Txt(when (label) { "粗体" -> "B"; "斜体" -> "I"; "标题" -> "H"; else -> label }, size = 13, weight = if (label == "粗体") FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

}

@Composable private fun NotebookHistorySheet(path: String, repository: Repository, dismiss: () -> Unit, restore: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var revisions by remember { mutableStateOf<List<NotebookRevision>>(emptyList()) }
    var preview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) { revisions = withContext(Dispatchers.IO) { repository.notebooks.revisions(path) } }
    Sheet(dismiss) { close ->
        if (preview != null) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp)) { Markdown(preview.orEmpty()) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("返回", Modifier.weight(1f), solid = false) { preview = null }
                ActionButton("恢复此版本", Modifier.weight(1f)) { close.after { restore(preview.orEmpty()) } }
            }
        } else LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(revisions, key = { it.id }) { revision -> Entry("clock", noteTime(revision.time)) { scope.launch { preview = withContext(Dispatchers.IO) { repository.notebooks.revision(path, revision.id) } } } }
            if (revisions.isEmpty()) item { Txt("还没有历史版本", Modifier.padding(18.dp), size = 13, color = Palette.Secondary) }
        }
    }
}

private fun noteTime(time: Long): String = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
