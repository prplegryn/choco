package app.chocola.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

private sealed interface PillControl { val key: String; val label: String }
private data class PillRange(override val key: String, override val label: String, val range: ClosedFloatingPointRange<Float>, val step: Float, val unit: String) : PillControl
private data class PillChoice(override val key: String, override val label: String, val values: Map<String, String>) : PillControl
private data class PillColor(override val key: String, override val label: String) : PillControl
private data class PillSwitch(override val key: String, override val label: String) : PillControl
private data class PillGroup(val name: String, val controls: List<PillControl>)
private val pillGroups = listOf(
    PillGroup("光效", listOf(
        PillChoice("style", "表面动效", statusStyles),
        PillRange("period", "循环周期", 600f..12000f, 50f, "ms"), PillRange("strength", "光效强度", 0f..1f, .01f, "%"),
        PillRange("band", "光场宽度", .15f..1.8f, .01f, "倍"), PillRange("angle", "偏转角度", -65f..65f, 1f, "°"),
        PillChoice("direction", "运动方向", linkedMapOf("forward" to "向右", "reverse" to "向左", "alternate" to "往返")),
        PillColor("lightA", "主光颜色"), PillColor("lightB", "副光颜色"), PillColor("lightC", "第三光颜色"))),
    PillGroup("文字", listOf(
        PillRange("fontSize", "字号", 10f..20f, .5f, "sp"), PillRange("fontWeight", "字重", 300f..700f, 100f, ""),
        PillRange("letterSpacing", "字距", -.5f..2f, .05f, "sp"), PillColor("ink", "文字颜色"),
        PillChoice("textEffect", "切换方式", linkedMapOf("fade" to "渐变交接", "glide" to "轻移渐变", "still" to "即时替换")),
        PillRange("textDuration", "切换时长", 100f..1000f, 10f, "ms"), PillRange("feather", "渐变羽化", 4f..80f, 1f, "dp"),
        PillChoice("textDirection", "揭示方向", linkedMapOf("forward" to "从左向右", "reverse" to "从右向左")))),
    PillGroup("形状", listOf(
        PillRange("height", "高度", 24f..56f, 1f, "dp"), PillRange("corner", "圆角", 0f..28f, 1f, "dp"),
        PillRange("padding", "左右内边距", 4f..32f, 1f, "dp"), PillSwitch("autoWidth", "宽度随内容变化"),
        PillRange("minWidth", "最小宽度", 48f..280f, 1f, "dp"), PillRange("maxWidth", "最大宽度", 48f..340f, 1f, "dp"),
        PillRange("fixedWidth", "固定宽度", 64f..340f, 1f, "dp"), PillColor("paper", "胶囊颜色"))),
    PillGroup("伸缩", listOf(
        PillChoice("widthCurve", "运动曲线", linkedMapOf("smooth" to "柔和", "ease" to "缓进缓出", "linear" to "匀速", "spring" to "弹性")),
        PillRange("widthDuration", "过渡时长", 160f..1200f, 10f, "ms"), PillRange("stiffness", "弹性速度", 80f..1000f, 10f, ""),
        PillRange("damping", "阻尼", .55f..1.3f, .01f, "倍"))),
    PillGroup("出现与消失", listOf(
        PillChoice("entrance", "运动方式", linkedMapOf("lift" to "轻抬渐显", "scale" to "缩放渐显", "fade" to "柔和渐显", "still" to "即时切换")),
        PillRange("appearanceDuration", "过渡时长", 100f..900f, 10f, "ms"), PillRange("travel", "位移距离", 0f..24f, .5f, "dp"),
        PillRange("startScale", "初始比例", .75f..1f, .005f, "%"))),
    PillGroup("阴影", listOf(
        PillRange("shadowBlur", "扩散半径", 1f..40f, 1f, "dp"), PillRange("shadowAlpha", "不透明度", 0f..0.35f, .005f, "%"),
        PillRange("shadowOffset", "垂直偏移", -8f..12f, .5f, "dp"), PillColor("shadowColor", "阴影颜色")))
)

private fun visibleControl(key: String, d: PillDesign): Boolean = when (key) {
    "period", "strength", "band", "lightA", "lightB", "lightC" -> d.style != "still"
    "angle", "direction" -> d.style !in setOf("still", "breath")
    "textDuration" -> d.textEffect != "still"
    "feather", "textDirection" -> d.textEffect != "still"
    "minWidth", "maxWidth" -> d.autoWidth
    "fixedWidth" -> !d.autoWidth
    "widthDuration" -> d.widthCurve != "spring"
    "stiffness", "damping" -> d.widthCurve == "spring"
    "appearanceDuration" -> d.entrance != "still"
    "travel" -> d.entrance == "lift"
    "startScale" -> d.entrance in setOf("lift", "scale")
    else -> true
}

@Composable fun StatusAppearancePage(state: AppState, repository: Repository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    var json by rememberSaveable { mutableStateOf(state.preferences.pill.json().toString()) }
    val design = remember(json) { PillDesign.from(JSONObject(json)) }
    val document = remember(design) { design.json() }
    fun change(key: String, value: Any) { json = design.json().put(key, value).toString() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sample by rememberSaveable { mutableIntStateOf(0) }
    var autoplay by rememberSaveable { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<PillChoice?>(null) }
    var coloring by remember { mutableStateOf<PillColor?>(null) }
    val samples = listOf("检查带宽与网络负载", "读取服务器状态", "核对配置", "整理检查结果", "保存运行记录")
    LaunchedEffect(autoplay) { while (autoplay) { delay(1900); sample = (sample + 1) % samples.size } }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    val previewHeight = maxOf(108f, design.height + 48).dp
    SettingsLayout(onBack, actions = {
            Press(Modifier.size(48.dp), radius = 24.dp, description = "复制当前动效配置", onClick = {
                val exported = JSONObject(design.export()).put("environment", JSONObject().put("appFontScale", state.preferences.fontScale)
                    .put("systemFontScale", density.fontScale).put("density", density.density)).toString(2)
                runCatching { clipboard.setText(AnnotatedString(exported)) }.onSuccess { copied = true }.onFailure { repository.notice("复制配置失败") }
            }) { Box(Modifier.size(42.dp).floatingSurface(21.dp, true), contentAlignment = Alignment.Center) { CopyGlyph(copied) } }
            IconButton("check", "保存状态指示器") { scope.launch { repository.preferences(repository.state.value.preferences.copy(statusDesign = design)); onBack() } }
    }, headerHeight = previewHeight + 110.dp, header = {
      Column {
        Box(Modifier.fillMaxWidth().height(previewHeight), contentAlignment = Alignment.Center) {
            StatusPill(samples[sample], visible, state.preferences.copy(statusDesign = design), preview = true)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center) {
            Press(Modifier.height(36.dp), onClick = { sample = (sample + 1) % samples.size }) { Txt("切换内容", Modifier.padding(horizontal = 10.dp), size = 12, color = Palette.Secondary) }
            Press(Modifier.height(36.dp), onClick = { visible = !visible }) { Txt(if (visible) "隐藏预览" else "显示预览", Modifier.padding(horizontal = 10.dp), size = 12, color = Palette.Secondary) }
            Press(Modifier.height(36.dp), onClick = { autoplay = !autoplay }) { Txt(if (autoplay) "暂停切换" else "连续预览", Modifier.padding(horizontal = 10.dp), size = 12, color = Palette.Secondary) }
        }
        Segments(listOf("预设", "自定义"), tab, { tab = it }, Modifier.padding(vertical = 12.dp))
      }
    }) { padding ->
        TabContent(tab, Modifier.fillMaxSize()) { selected ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                if (selected == 0) {
                    items(statusStyles.entries.toList(), key = { it.key }) { item ->
                        Press(Modifier.fillMaxWidth().height(52.dp), onClick = { json = PillDesign.preset(item.key).json().toString() }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Txt(item.value, Modifier.weight(1f), size = 14)
                                if (design.style == item.key) Glyph("check", Modifier.size(18.dp))
                            }
                        }
                    }
                } else {
                    pillGroups.forEach { group ->
                        item("group:${group.name}") { GroupLabel(group.name) }
                        items(group.controls.filter { visibleControl(it.key, design) }, key = { it.key }) { control ->
                            when (control) {
                                is PillChoice -> Entry(text = control.label, value = control.values[document.optString(control.key)].orEmpty()) { choosing = control }
                                is PillColor -> Press(Modifier.fillMaxWidth().height(54.dp), onClick = { coloring = control }) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Txt(control.label, Modifier.weight(1f), size = 14)
                                        Txt(document.getString(control.key), size = 12, color = Palette.Secondary, mono = true)
                                        Box(Modifier.size(24.dp).background(pillColor(document.getString(control.key)), CircleShape).border(1.dp, Palette.Border, CircleShape))
                                    }
                                }
                                is PillSwitch -> ToggleEntry(control.label, document.getBoolean(control.key)) { change(control.key, it) }
                                is PillRange -> {
                                    val value = document.getDouble(control.key).toFloat()
                                    val display = if (control.unit == "%") "${(value * 100).roundToInt()}%" else {
                                        val number = if (control.step >= 1) value.roundToInt().toString() else String.format(java.util.Locale.ROOT, if (control.step < .1f) "%.2f" else "%.1f", value)
                                        "$number ${control.unit}".trim()
                                    }
                                    SettingSlider(control.label, value, control.range, display) { change(control.key, ((it / control.step).roundToInt() * control.step).toDouble()) }
                                }
                            }
                        }
                    }
                    item { Entry("undo", "恢复当前款式的预设") { json = PillDesign.preset(design.style).json().toString() } }
                }
            }
        }
    }
    choosing?.let { control -> Sheet({ choosing = null }) { close ->
        LazyColumn(Modifier.heightIn(max = 416.dp)) {
            items(control.values.entries.toList(), key = { it.key }) { option ->
                Press(Modifier.fillMaxWidth().height(50.dp), onClick = { change(control.key, option.key); close() }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(option.value, Modifier.weight(1f), size = 14)
                        if (document.optString(control.key) == option.key) Glyph("check", Modifier.size(18.dp))
                    }
                }
            }
        }
    } }
    coloring?.let { control ->
        ColorPickerSheet(control.label, document.getString(control.key), { coloring = null }) { change(control.key, it) }
    }
}
