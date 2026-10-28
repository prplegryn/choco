package app.chocola.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chocola.agent.WorkspacePaths
import app.chocola.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable fun FilesPage(repository: Repository, state: AppState, relative: String, onBack: () -> Unit, onDirectory: (String) -> Unit,
    onFile: (String) -> Unit, onImport: () -> Unit, onShare: (File) -> Unit) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    val paths = remember(state.currentId) { WorkspacePaths(repository.workspace(state.currentId)) }
    val directory = remember(relative, state.currentId) { paths.resolve(relative) }
    LaunchedEffect(relative, refresh, state.runningId, state.filesVersion) {
        files = withContext(Dispatchers.IO) { directory.listFiles().orEmpty().filter { runCatching { paths.resolve(it.relativeTo(paths.root).invariantSeparatorsPath) }.isSuccess }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(onBack, center = { if (relative.isNotBlank()) Txt(directory.name, size = 14, maxLines = 1) }, actions = {
            IconButton("undo", "撤销上次文件改动") { scope.launch { runCatching { repository.engine.tools.undo(state.currentId) }.onSuccess { repository.notice(if (it) "已撤销" else "没有可撤销的改动"); refresh++ }.onFailure { repository.notice(it.message) } } }
            IconButton("upload", "导入文件", onClick = onImport)
            IconButton("plus", "新建文件或目录") { create = true }
        })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
            items(files, key = { it.absolutePath }) { file ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Press(Modifier.weight(1f).heightIn(min = 68.dp), onClick = { if (file.isDirectory) onDirectory(file.relativeTo(paths.root).invariantSeparatorsPath) else onFile(file.absolutePath) }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(if (file.isDirectory) "folder" else "file"); Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Txt(file.name, size = 14, maxLines = 1)
                                Txt(if (file.isDirectory) "文件夹" else formatBytes(file.length()), size = 12, color = Palette.Secondary)
                            }
                        }
                    }
                    IconButton("more", "文件操作", Modifier.size(42.dp), color = Palette.Secondary) { chosen = file }
                }
            }
            if (files.isEmpty()) item { Entry("upload", "导入文件", onClick = onImport) }
        }
    }
    if (create) {
        var name by rememberSaveable { mutableStateOf("") }
        var kind by rememberSaveable { mutableIntStateOf(0) }
        Sheet({ create = false }) { close ->
            Segments(listOf("文件", "文件夹"), kind, { kind = it })
            Spacer(Modifier.height(16.dp)); Field("", name, { name = it }, placeholder = "名称")
            Spacer(Modifier.height(16.dp))
            ActionButton("创建", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
                scope.launch {
                    runCatching {
                        require(!name.contains('/') && name !in setOf(".", "..")) { "名称不合法" }
                        val file = paths.resolve(File(directory, name).relativeTo(paths.root).invariantSeparatorsPath)
                        require(!file.exists()) { "同名项目已存在" }
                        withContext(Dispatchers.IO) { if (kind == 1) check(file.mkdir()) else check(file.createNewFile()) }
                        refresh++; close()
                    }.onFailure { repository.notice(it.message) }
                }
            }
        }
    }
    chosen?.let { file -> Sheet({ chosen = null }) { close ->
        if (file.isFile) Entry("share", "分享文件") { close.after { onShare(file) } }
        Entry("edit", "重命名") { close.after { renaming = file } }
        Entry("trash", "删除", danger = true) { close.after { deleting = file } }
    } }
    deleting?.let { file -> ConfirmSheet("删除“${file.name}”？", "删除", { deleting = null }) {
        scope.launch { withContext(Dispatchers.IO) { file.deleteRecursively() }; refresh++ }
    } }
    renaming?.let { file ->
        var name by rememberSaveable(file.path) { mutableStateOf(file.name) }
        Sheet({ renaming = null }) { close ->
            Field("", name, { name = it }, placeholder = "名称"); Spacer(Modifier.height(16.dp))
            ActionButton("保存", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) { scope.launch {
                runCatching {
                    require(!name.contains('/') && name !in setOf(".", "..")) { "名称不合法" }
                    val dest = paths.resolve(File(file.parentFile, name).relativeTo(paths.root).invariantSeparatorsPath)
                    require(!dest.exists()) { "同名项目已存在" }; check(file.renameTo(dest)) { "重命名失败" }; refresh++; close()
                }.onFailure { repository.notice(it.message) }
            } }
        }
    }
}
@Composable fun FilePage(repository: Repository, session: String, path: String, onBack: () -> Unit, onShare: (File) -> Unit) {
    val scope = rememberCoroutineScope()
    val paths = remember(session) { WorkspacePaths(repository.workspace(session)) }
    val file = remember(path) { paths.resolve(File(path).relativeTo(paths.root).invariantSeparatorsPath) }
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var editable by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            val ext = file.extension.lowercase()
            if (ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(path, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1800).coerceAtLeast(1) }
                image = BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            } else if (file.length() <= 2_000_000) {
                val bytes = file.readBytes()
                if (!bytes.take(8192).contains(0.toByte())) { text = bytes.toString(Charsets.UTF_8); original = text; editable = true }
            }
            loaded = true
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
        TopBar(onBack, center = { Txt(file.name, size = 14, maxLines = 1) }, actions = {
            IconButton("share", "分享文件") { onShare(file) }
            if (editable) IconButton("check", "保存文件") { scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { check(file.readText() == original) { "文件已被其它操作修改，请重新打开后编辑" } }
                    repository.engine.tools.execute(Session(id = session, access = Access.FULL), ToolCall(newId(), "write_file", JSONObject().put("path", paths.guest(file)).put("content", text).toString())) {}
                    original = text
                    repository.notice("已保存")
                }.onFailure { repository.notice(it.message) }
            } }
        })
        when {
            image != null -> Image(requireNotNull(image), contentDescription = file.name, Modifier.fillMaxSize().padding(20.dp))
            editable -> BasicTextField(text, { text = it }, Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, color = Palette.Ink), cursorBrush = SolidColor(Palette.Ink))
            loaded -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(formatBytes(file.length()), size = 13, color = Palette.Secondary)
                ActionButton("分享文件", Modifier.fillMaxWidth()) { onShare(file) }
            }
        }
    }
}
private fun formatBytes(size: Long): String = when { size >= 1_048_576 -> String.format(java.util.Locale.ROOT, "%.1f MB", size / 1048576.0); size >= 1024 -> "${size / 1024} KB"; else -> "$size B" }
