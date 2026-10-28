package app.chocola.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable internal fun ColorPickerSheet(label: String, initial: String, onDismiss: () -> Unit, onColor: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial.uppercase()) }
    val initialHsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(runCatching { android.graphics.Color.parseColor(initial) }.getOrDefault(android.graphics.Color.BLACK), it) } }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var area by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun update(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h; saturation = s; brightness = v
        text = "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xffffff)
    }
    fun at(point: Offset) { update(s = (point.x / area.width.coerceAtLeast(1)).coerceIn(0f, 1f), v = (1 - point.y / area.height.coerceAtLeast(1)).coerceIn(0f, 1f)) }
    val colors = listOf("#FFFFFF", "#B5B8C0", "#65676E", "#222326", "#000000", "#E34E59", "#A52D49", "#F29B4B", "#D4B253", "#8A6845", "#C4D751", "#5C9B60", "#3E7E67", "#3BA7A1", "#55B4D0", "#608AD4", "#355DA1", "#6C61CA", "#A775CE", "#D17EAB")
    Sheet(onDismiss) { close ->
      Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(label, Modifier.weight(1f), size = 15)
            Box(Modifier.size(32.dp).background(runCatching { Color(android.graphics.Color.parseColor(text)) }.getOrDefault(Color.Black), CircleShape).border(1.dp, Palette.Border, CircleShape))
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).onSizeChanged { area = it }
            .pointerInput(hue) { detectTapGestures { at(it) } }
            .pointerInput(hue) { detectDragGestures(onDragStart = { at(it) }) { c, _ -> c.consume(); at(c.position) } }) {
            val hueColor = Color.hsv(hue, 1f, 1f)
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val point = Offset(saturation * size.width, (1 - brightness) * size.height)
            drawCircle(Color.Black.copy(alpha = .3f), 7.dp.toPx(), point, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 7.dp.toPx(), point, style = Stroke(2.dp.toPx()))
        }
        SettingSlider("色相", hue, 0f..359.9f, "${hue.roundToInt()}°") { update(h = it) }
        colors.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { row.forEach { value ->
                Press(Modifier.size(36.dp), radius = 18.dp, description = value, onClick = {
                    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(value), hsv)
                    update(hsv[0], hsv[1], hsv[2])
                }) { Box(Modifier.size(25.dp).background(Color(android.graphics.Color.parseColor(value)), CircleShape).border(1.dp, Palette.Border, CircleShape)) }
            } }
        }
        Field("", text, { value ->
            text = value.trim().uppercase()
            if (text.matches(Regex("#[0-9A-F]{6}"))) {
                val hsv = FloatArray(3); android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(text), hsv)
                hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
            }
        }, placeholder = "#RRGGBB")
      }
        Spacer(Modifier.height(10.dp))
        ActionButton("应用颜色", Modifier.fillMaxWidth(), enabled = text.matches(Regex("#[0-9A-F]{6}([0-9A-F]{2})?"))) { close.after { onColor(text) } }
    }
}
