package app.chocola.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

@Composable internal fun ChatBackgroundPage(state: AppState, repository: Repository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var document by rememberSaveable { mutableStateOf(state.preferences.chatBackground.json().toString()) }
    val design = remember(document) { ChatBackground.from(JSONObject(document)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val keepImage = remember { java.util.concurrent.atomic.AtomicReference(state.preferences.chatBackground.image) }
    val created = remember { mutableListOf<String>() }
    fun change(d: ChatBackground) { document = d.normalized().json().toString() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val (path, tone) = withContext(Dispatchers.IO) { WallpaperImages.import(context, uri) }
                created.add(path)
                change(design.copy(image = path, imageTone = tone, preset = "photo", horizontal = .5f, zoom = 1f))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { repository.notice(e.message ?: "图片未能读取") }
            finally { importing = false }
        }
    }
    DisposableEffect(Unit) { onDispose { created.filter { it != keepImage.get() }.forEach { File(it).delete() } } }
    BackHandler(expanded) { expanded = false }
    val previewView = LocalView.current
    val previewInk = wallpaperLightText(design)
    DisposableEffect(expanded, previewInk) {
        val window = (context as? android.app.Activity)?.window
        if (expanded && window != null) androidx.core.view.WindowCompat.getInsetsController(window, previewView).apply {
            isAppearanceLightStatusBars = !previewInk; isAppearanceLightNavigationBars = !previewInk
        }
        onDispose {
            if (window != null) androidx.core.view.WindowCompat.getInsetsController(window, previewView).apply {
                isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
            }
        }
    }
    val previewHeight = ((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 100) * .43f).dp.coerceIn(200.dp, 350.dp)
    SettingsLayout(onBack, actions = {
        IconButton("check", "保存聊天背景", enabled = !importing && !saving) {
            saving = true
            scope.launch {
                try {
                    repository.preferences(repository.state.value.preferences.copy(chatBackground = design))
                    keepImage.set(design.image); onBack()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { repository.notice(e.message); saving = false }
            }
        }
    }, headerHeight = previewHeight + 114.dp, header = {
      Column {
        Press(Modifier.fillMaxWidth().height(previewHeight).padding(vertical = 8.dp), radius = 24.dp,
            description = "放大完整聊天预览", onClick = { expanded = true }) { ChatBackgroundPreview(design, state.preferences) }
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("聊天背景", Modifier.weight(1f), size = 14, weight = FontWeight.Medium)
            Press(Modifier.height(36.dp), enabled = !importing, onClick = { picker.launch("image/*") }) {
                Txt(if (importing) "正在读取" else "选择图片", Modifier.padding(horizontal = 10.dp), size = 12)
            }
            if (design.image.isNotBlank()) IconButton("close", "移除背景图片", Modifier.size(36.dp)) { change(design.copy(image = "", preset = "custom")) }
        }
        Segments(listOf("预设", "自定义"), tab, { tab = it }, Modifier.padding(vertical = 6.dp))
      }
    }) { padding ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                    if (tab == 0) {
                        items(ChatBackground.presets.entries.toList(), key = { it.key }) { (key, title) ->
                            Press(Modifier.fillMaxWidth().height(50.dp), onClick = { change(ChatBackground.preset(key, design)) }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Txt(title, Modifier.weight(1f), size = 14)
                                    if (design.preset == key) Glyph("check", Modifier.size(18.dp))
                                }
                            }
                        }
                    } else {
                        if (design.image.isNotBlank()) {
                            item { GroupLabel("图片位置") }
                            item { BackgroundRange("左右位置", design.horizontal, 0f..1f, "${(design.horizontal * 100).roundToInt()}%") { change(design.copy(horizontal = it, preset = "custom")) } }
                            item { BackgroundRange("缩放", design.zoom, 1f..1.8f, "${(design.zoom * 100).roundToInt()}%") { change(design.copy(zoom = it, preset = "custom")) } }
                            item { BackgroundRange("模糊", design.blur, 0f..48f, "${design.blur.roundToInt()}") { change(design.copy(blur = it, preset = "custom")) } }
                        }
                        item { GroupLabel("明暗与色彩") }
                        item { BackgroundRange("明暗", design.brightness, -.65f.. .65f, "${(design.brightness * 100).roundToInt()}") { change(design.copy(brightness = it, preset = "custom")) } }
                        item { BackgroundRange("对比度", design.contrast, .5f..1.8f, "${(design.contrast * 100).roundToInt()}%") { change(design.copy(contrast = it, preset = "custom")) } }
                        item { BackgroundRange("饱和度", design.saturation, 0f..1.8f, "${(design.saturation * 100).roundToInt()}%") { change(design.copy(saturation = it, preset = "custom")) } }
                        item { BackgroundRange("冷暖", design.warmth, -.5f.. .5f, "${(design.warmth * 100).roundToInt()}") { change(design.copy(warmth = it, preset = "custom")) } }
                        item { BackgroundColorRow("底色", design.color) { change(design.copy(color = it, preset = "custom")) } }
                        item { BackgroundColorRow("渐变尾色", design.colorEnd) { change(design.copy(colorEnd = it, preset = "custom")) } }
                        item { BackgroundRange("渐变方向", design.angle, 0f..360f, "${design.angle.roundToInt()}°") { change(design.copy(angle = it, preset = "custom")) } }
                        item { GroupLabel("柔化与阅读") }
                        item { BackgroundColorRow("蒙层颜色", design.veilColor) { change(design.copy(veilColor = it, preset = "custom")) } }
                        item { BackgroundRange("蒙层浓度", design.veil, 0f..1f, "${(design.veil * 100).roundToInt()}%") { change(design.copy(veil = it, preset = "custom")) } }
                        item { Segments(listOf("自动文字", "深色文字", "浅色文字"), listOf("auto", "dark", "light").indexOf(design.text).coerceAtLeast(0), { change(design.copy(text = listOf("auto", "dark", "light")[it], preset = "custom")) }, Modifier.padding(vertical = 10.dp)) }
                        item { BackgroundRange("边缘渐变浓度", design.edgeOpacity, 0f..1f, "${(design.edgeOpacity * 100).roundToInt()}%") { change(design.copy(edgeOpacity = it, preset = "custom")) } }
                        item { BackgroundRange("边缘渐变范围", design.edgeDepth, 48f..220f, "${design.edgeDepth.roundToInt()}") { change(design.copy(edgeDepth = it, preset = "custom")) } }
                        item { Entry("undo", "恢复浅纸背景") { change(ChatBackground()) } }
                    }
                }
    }
    if (expanded) {
        Box(Modifier.fillMaxSize().background(Palette.Canvas)) {
            ChatBackgroundPreview(design, state.preferences, Modifier.fillMaxSize(), rounded = false)
            IconButton("close", "关闭完整预览", Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(14.dp), floating = true) { expanded = false }
        }
    }
}

@Composable private fun BackgroundRange(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, change: (Float) -> Unit) =
    SettingSlider(label, value, range, display, change)

@Composable private fun BackgroundColorRow(label: String, value: String, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Entry(text = label, value = value) { open = true }
    if (open) ColorPickerSheet(label, value, { open = false }, change)
}

/** Measures the sample at the real window size and scales the entire frame, including system bars. */
@Composable internal fun ChatBackgroundPreview(design: ChatBackground, preferences: Preferences, modifier: Modifier = Modifier, rounded: Boolean = true) {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = view.rootView.width.takeIf { it > 0 } ?: with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeight = view.rootView.height.takeIf { it > 0 } ?: with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val status = with(density) { WindowInsets.statusBars.getTop(this).toDp() }.coerceAtLeast(24.dp)
    val nav = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }.coerceAtLeast(20.dp)
    val previewBackdrop = rememberLayerBackdrop()
    val corner = if (android.os.Build.VERSION.SDK_INT >= 31) view.rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0 else 0
    val safeEdge = with(density) { corner.toDp() * .65f + 8.dp }.coerceAtLeast(26.dp)
    val ink = if (wallpaperLightText(design)) Color.White else Palette.Ink
    Layout(content = {
        CompositionLocalProvider(LocalControlBackdrop provides previewBackdrop) {
        Box(Modifier.fillMaxSize()) {
          Box(Modifier.fillMaxSize().then(if (preferences.floating.style == "glass" && android.os.Build.VERSION.SDK_INT >= 31) Modifier.layerBackdrop(previewBackdrop) else Modifier)) {
            ChatWallpaper(design)
            Column(Modifier.fillMaxSize().padding(top = status + 82.dp, bottom = nav + 190.dp, start = 22.dp, end = 22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.background(Color(0xFFF0F0F2), RoundedCornerShape(20.dp)).padding(15.dp)) { Txt("做一个简洁的网页", size = 16) }
                }
                Txt("已经完成。页面使用清晰的排版与柔和的留白，手机和电脑都可以直接打开。", size = 16, lineHeight = 27, color = ink)
                Row(Modifier.fillMaxWidth().background(operationFill, RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("已完成 3 项操作", Modifier.weight(1f), size = 13, color = Palette.Secondary)
                    ExpandControl(false, "展开示例操作", group = true) {}
                }
            }
            ScreenEdgeVeils(design)
          }
            Row(Modifier.fillMaxWidth().padding(top = status + 8.dp, start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton("menu", "会话", floating = true) {}
                Spacer(Modifier.weight(1f))
                Row(Modifier.floatingSurface(24.dp, true).height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton("edit", "新会话") {}; IconButton("more", "会话菜单") {}
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = nav + 12.dp)) {
                Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) { StatusPill("整理页面布局", true, preferences, preview = true) }
                Column(Modifier.fillMaxWidth().floatingSurface(28.dp, allowGlass = false).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
                    Txt("发消息", Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 9.dp).heightIn(min = 27.dp), size = 16, color = Palette.Secondary)
                    ComposerActions("手动确认", .5f, false, true)
                }
            }
            if (rounded) Row(Modifier.fillMaxWidth().height(status).padding(horizontal = safeEdge), verticalAlignment = Alignment.CenterVertically) {
                Txt("9:41", Modifier.weight(1f), size = 13, weight = FontWeight.Medium, color = ink)
                androidx.compose.foundation.Canvas(Modifier.size(51.dp, 14.dp)) {
                    repeat(4) { i -> drawRoundRect(ink, androidx.compose.ui.geometry.Offset(i * 4.dp.toPx(), (9 - i * 2).dp.toPx()), androidx.compose.ui.geometry.Size(2.5.dp.toPx(), (3 + i * 2).dp.toPx()), androidx.compose.ui.geometry.CornerRadius(.6.dp.toPx())) }
                    drawRoundRect(ink, androidx.compose.ui.geometry.Offset(25.dp.toPx(), 3.dp.toPx()), androidx.compose.ui.geometry.Size(21.dp.toPx(), 9.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    drawRoundRect(ink, androidx.compose.ui.geometry.Offset(27.dp.toPx(), 5.dp.toPx()), androidx.compose.ui.geometry.Size(15.dp.toPx(), 5.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
                }
            }
            if (rounded) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp).size(108.dp, 4.dp).background(ink.copy(alpha = .7f), CircleShape))
        }
        }
    }, modifier = modifier.then(if (rounded) Modifier.softShadow(24.dp).graphicsLayer {
        shape = RoundedCornerShape(24.dp); clip = true; compositingStrategy = CompositingStrategy.Offscreen
    } else Modifier)) { children, constraints ->
        val scale = minOf(constraints.maxWidth.toFloat() / screenWidth, constraints.maxHeight.toFloat() / screenHeight)
        val child = children.single().measure(Constraints.fixed(screenWidth, screenHeight))
        layout((screenWidth * scale).roundToInt(), (screenHeight * scale).roundToInt()) {
            child.placeRelativeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
        }
    }
}
