package app.chocola.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable internal fun HistorySwitcher(selected: Int, onSelect: (Int) -> Unit) {
    val position = animateFloatAsState(selected.toFloat(), tween(340, easing = Motion.Travel), label = "historyTab")
    Box(Modifier.fillMaxWidth().height(44.dp).background(Color(0xFFEDEEF0), RoundedCornerShape(22.dp)).padding(3.dp)) {
        Layout(content = { Box(Modifier.fillMaxSize().softShadow(19.dp, true, opacity = .06f).background(Color.White, RoundedCornerShape(19.dp))) },
            modifier = Modifier.fillMaxSize()) { children, constraints ->
            val w = constraints.maxWidth / 2
            val child = children.single().measure(Constraints.fixed(w, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) { child.placeRelative((w * position.value).roundToInt(), 0) }
        }
        Row(Modifier.fillMaxSize()) {
            listOf("历史会话", "定时任务").forEachIndexed { index, label ->
                Press(Modifier.weight(1f).fillMaxHeight(), radius = 19.dp, onClick = { onSelect(index) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Glyph(if (index == 0) "context" else "clock", Modifier.size(15.dp), if (selected == index) Palette.Ink else Palette.Secondary)
                        Txt(label, size = 12, weight = if (selected == index) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected == index) Palette.Ink else Palette.Secondary)
                    }
                }
            }
        }
    }
}
