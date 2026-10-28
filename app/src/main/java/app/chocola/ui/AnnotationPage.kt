package app.chocola.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chocola.data.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.*

private val annotationTools = linkedMapOf("pen" to "画笔", "highlight" to "荧光笔", "arrow" to "箭头", "rectangle" to "矩形", "ellipse" to "圆形", "text" to "文字", "erase" to "橡皮", "move" to "移动")

private fun imageFrame(view: IntSize, image: Bitmap, zoom: Float, pan: Offset): Rect {
    val fit = minOf(view.width.toFloat() / image.width, view.height.toFloat() / image.height)
    val w = image.width * fit * zoom; val h = image.height * fit * zoom
    val left = (view.width - w) / 2 + pan.x; val top = (view.height - h) / 2 + pan.y
    return Rect(left, top, left + w, top + h)
}

private fun markHit(mark: AnnotationMark, point: AnnotationPoint): Boolean {
    if (mark.points.isEmpty()) return false
    val margin = maxOf(mark.width * (if (mark.tool == "highlight") 3 else 1), .025f)
    val minX = mark.points.minOf { it.x }; val maxX = mark.points.maxOf { it.x }
    val minY = mark.points.minOf { it.y }; val maxY = mark.points.maxOf { it.y }
    if (mark.tool == "text") return point.x in minX - margin..(maxX + mark.text.length * mark.textSize * .65f) && point.y in minY - margin..(maxY + mark.textSize * maxOf(1, mark.text.lines().size))
    if (point.x !in minX - margin..maxX + margin || point.y !in minY - margin..maxY + margin) return false
    if (mark.tool == "rectangle") return minOf(abs(point.x - minX), abs(point.x - maxX), abs(point.y - minY), abs(point.y - maxY)) < margin
    if (mark.tool == "ellipse") {
        val rx = ((maxX - minX) / 2).coerceAtLeast(.001f); val ry = ((maxY - minY) / 2).coerceAtLeast(.001f)
        val radius = sqrt(((point.x - (minX + maxX) / 2) / rx).pow(2) + ((point.y - (minY + maxY) / 2) / ry).pow(2))
        return abs(radius - 1) * minOf(rx, ry) < margin
    }
    if (mark.points.size == 1) return hypot(point.x - minX, point.y - minY) < margin
    return mark.points.zipWithNext().any { (a, b) ->
        val dx = b.x - a.x; val dy = b.y - a.y
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / (dx * dx + dy * dy).coerceAtLeast(.000001f)).coerceIn(0f, 1f)
        hypot(point.x - (a.x + t * dx), point.y - (a.y + t * dy)) < margin
    }
}

@Composable internal fun AnnotationPage(image: Attachment, repository: Repository, onDone: (Attachment) -> Unit) {
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    var editor by remember(image.draftId) { mutableStateOf<AnnotationSession?>(null) }
    val empty = remember { MutableStateFlow(AnnotationHistory()) }
    val history by (editor?.state ?: empty).collectAsStateWithLifecycle()
    val marks = remember(history) { history.marks() }
    var bitmap by remember(image.draftId) { mutableStateOf<Bitmap?>(null) }
    var tool by rememberSaveable { mutableStateOf("pen") }
    var color by rememberSaveable { mutableLongStateOf(0xFFE04444) }
    var stroke by rememberSaveable { mutableFloatStateOf(.006f) }
    var textSize by rememberSaveable { mutableFloatStateOf(.045f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var live by remember { mutableStateOf<AnnotationMark?>(null) }
    var erased by remember { mutableStateOf(setOf<String>()) }
    var options by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var textAt by remember { mutableStateOf<AnnotationPoint?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    fun work(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { repository.logs.error("annotation.failed", e); repository.notice(e.message ?: "标注保存失败") } } }
    fun finish() {
        if (saving) return
        val current = editor
        if (current == null) { onDone(image); return }
        saving = true
        work { try { current.flush(); onDone(AnnotationRenderer.export(image, current.state.value)) } finally { saving = false } }
    }
    BackHandler(onBack = ::finish)
    LaunchedEffect(image.draftId) {
        try { editor = repository.annotations.open(image.draftId); bitmap = AnnotationRenderer.preview(image) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { repository.logs.error("annotation.open.failed", e); repository.notice(e.message ?: "图片无法打开") }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, editor) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) repository.scope.launch { editor?.flush() } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val currentMarks by rememberUpdatedState(marks)
    val drawing = remember(marks, bitmap, erased) { bitmap?.let { AnnotationDrawing(marks.filterNot { mark -> mark.id in erased }, it.width.toFloat(), it.height.toFloat()) } }
    val liveDrawing = remember(live, bitmap) { bitmap?.let { imageBitmap -> live?.let { AnnotationDrawing(listOf(it), imageBitmap.width.toFloat(), imageBitmap.height.toFloat()) } } }
    DisposableEffect(tool) { onDispose { live = null; erased = emptySet() } }
    CompositionLocalProvider(LocalControlBackdrop provides backdrop) {
        SettingsLayout(::finish, compactTop = true, actions = {
            IconButton("undo", "撤回", enabled = history.canUndo && !saving) { editor?.undo() }
            IconButton("redo", "重做", enabled = history.canRedo && !saving) { editor?.redo() }
            IconButton("check", "完成标注", enabled = !saving, onClick = ::finish)
        }, footer = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Press(Modifier.size(34.dp).floatingSurface(17.dp, compact = true), description = "颜色与线条", onClick = { options = true }) {
                        Box(Modifier.size(17.dp).background(Color(color.toInt()), RoundedCornerShape(9.dp)))
                    }
                    Spacer(Modifier.weight(1f))
                    Press(Modifier.height(30.dp).padding(horizontal = 10.dp), radius = 15.dp, description = "重置缩放", onClick = { zoom = 1f; pan = Offset.Zero }) {
                        Txt(if (saving) "正在保存" else "${(zoom * 100).roundToInt()}%", size = 11, color = Palette.Secondary)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton("trash", "清空标注", Modifier.size(34.dp), enabled = marks.isNotEmpty() && !saving, floating = false) { clear = true }
                }
                Row(Modifier.fillMaxWidth().floatingSurface(100.dp, compact = true).padding(horizontal = 7.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    annotationTools.forEach { (value, label) ->
                        Press(Modifier.weight(1f).height(36.dp).background(if (tool == value) Palette.Ink else Color.Transparent, RoundedCornerShape(18.dp)),
                            enabled = editor != null && bitmap != null && !saving, radius = 18.dp, description = label, onClick = { tool = value }) {
                            AnnotationGlyph(value, if (tool == value) Color.White else Palette.Ink)
                        }
                    }
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).padding(padding).padding(bottom = 94.dp)) {
                val photo = bitmap
                if (photo != null) Canvas(Modifier.fillMaxSize().onSizeChanged { view = it }.pointerInput(tool, color, stroke, textSize, photo, editor, saving) {
                    if (saving) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val initial = imageFrame(view, photo, zoom, pan)
                        val inside = initial.contains(down.position)
                        fun point(position: Offset): AnnotationPoint {
                            val frame = imageFrame(view, photo, zoom, pan)
                            return AnnotationPoint(((position.x - frame.left) / frame.width).coerceIn(0f, 1f), ((position.y - frame.top) / frame.height).coerceIn(0f, 1f))
                        }
                        var points = listOf(point(down.position))
                        val markId = newId()
                        var multiple = false
                        var pressed: Boolean
                        if (inside && tool !in setOf("text", "move", "erase")) live = AnnotationMark(markId, tool, points, color, stroke)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) {
                                multiple = true; live = null; erased = emptySet()
                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                pan += event.calculatePan()
                            } else if (!multiple) event.changes.firstOrNull { it.id == down.id }?.let { change ->
                                val p = point(change.position)
                                when {
                                    tool == "move" -> pan += change.positionChange()
                                    inside && tool == "erase" -> currentMarks.lastOrNull { it.id !in erased && markHit(it, p) }?.let { erased = erased + it.id }
                                    inside && tool !in setOf("text", "move") -> {
                                        if (points.size > 3000) points = points.filterIndexed { index, _ -> index % 2 == 0 }
                                        points = if (tool in setOf("arrow", "rectangle", "ellipse")) listOf(points.first(), p) else points + p
                                        live = AnnotationMark(markId, tool, points, color, stroke)
                                    }
                                }
                            }
                            val fit = minOf(view.width.toFloat() / photo.width, view.height.toFloat() / photo.height)
                            val x = maxOf(0f, (photo.width * fit * zoom - view.width) / 2)
                            val y = maxOf(0f, (photo.height * fit * zoom - view.height) / 2)
                            pan = Offset(pan.x.coerceIn(-x, x), pan.y.coerceIn(-y, y))
                            event.changes.forEach { it.consume() }
                            pressed = event.changes.any { it.pressed }
                        } while (pressed)
                        if (!multiple && inside) when (tool) {
                            "text" -> { text = ""; textAt = point(down.position) }
                            "erase" -> if (erased.isNotEmpty()) editor?.append(AnnotationAction("remove", targets = erased.toList()))
                            "move" -> Unit
                            else -> live?.let { editor?.append(AnnotationAction("add", it)) }
                        }
                        live = null; erased = emptySet()
                    }
                }) {
                    val frame = imageFrame(view, photo, zoom, pan)
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val saved = native.save()
                        native.clipRect(0f, 0f, size.width, size.height)
                        native.translate(frame.left, frame.top)
                        native.scale(frame.width / photo.width, frame.height / photo.height)
                        native.drawBitmap(photo, 0f, 0f, null)
                        drawing?.draw(native); liveDrawing?.draw(native)
                        native.restoreToCount(saved)
                    }
                }
            }
        }
    }
    if (options) Sheet({ options = false }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0xFFE04444, 0xFF2176E8, 0xFF2D9C68, 0xFFFFA530, 0xFF9759D6, 0xFF111111, 0xFFFFFFFF).forEach { value ->
                Press(Modifier.size(31.dp).background(Color(value.toInt()), RoundedCornerShape(16.dp)), radius = 16.dp, description = "选择颜色", onClick = { color = value }) {
                    if (color == value) Glyph("check", Modifier.size(15.dp), if (value == 0xFFFFFFFFL) Palette.Ink else Color.White)
                }
            }
        }
        SettingSlider("线条粗细", stroke, .002f.. .02f, "${(stroke * 1000).roundToInt()}") { stroke = it }
        if (tool == "text") SettingSlider("文字大小", textSize, .02f.. .1f, "${(textSize * 1000).roundToInt()}") { textSize = it }
    }
    textAt?.let { point -> Sheet({ textAt = null }) {
        Field("文字", text, { text = it.take(2000) }, lines = 4)
        ActionButton("添加", Modifier.fillMaxWidth().padding(top = 12.dp), enabled = text.isNotBlank()) {
            editor?.append(AnnotationAction("add", AnnotationMark(tool = "text", points = listOf(point), color = color, width = stroke, text = text, textSize = textSize)))
            textAt = null
        }
    } }
    if (clear) ConfirmSheet("清空当前标注？", "清空", { clear = false }) { editor?.append(AnnotationAction("clear")); clear = false }
}

@Composable private fun AnnotationGlyph(tool: String, color: Color) {
    if (tool == "text") { Txt("T", size = 19, color = color); return }
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val h = size.height; val stroke = 1.65.dp.toPx()
        when (tool) {
            "rectangle" -> drawRoundRect(color, Offset(w * .14f, h * .2f), Size(w * .72f, h * .6f), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
            "ellipse" -> drawOval(color, Offset(w * .14f, h * .14f), Size(w * .72f, h * .72f), style = Stroke(stroke))
            "arrow" -> {
                drawLine(color, Offset(w * .2f, h * .8f), Offset(w * .8f, h * .2f), stroke, StrokeCap.Round)
                drawPath(Path().apply { moveTo(w * .37f, h * .2f); lineTo(w * .8f, h * .2f); lineTo(w * .8f, h * .63f) }, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            "move" -> {
                drawLine(color, Offset(w * .1f, h / 2), Offset(w * .9f, h / 2), stroke, StrokeCap.Round)
                drawLine(color, Offset(w / 2, h * .1f), Offset(w / 2, h * .9f), stroke, StrokeCap.Round)
                drawPath(Path().apply { moveTo(w * .32f, h * .28f); lineTo(w * .5f, h * .1f); lineTo(w * .68f, h * .28f); moveTo(w * .72f, h * .32f); lineTo(w * .9f, h * .5f); lineTo(w * .72f, h * .68f) }, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            else -> {
                val path = Path().apply { moveTo(w * .18f, h * .82f); lineTo(w * .25f, h * .56f); lineTo(w * .68f, h * .13f); lineTo(w * .88f, h * .33f); lineTo(w * .45f, h * .76f); close() }
                drawPath(path, color, style = Stroke(if (tool == "highlight") stroke * 1.5f else stroke, cap = StrokeCap.Round))
                if (tool == "erase") drawLine(color, Offset(w * .25f, h * .56f), Offset(w * .45f, h * .76f), stroke)
            }
        }
    }
}
