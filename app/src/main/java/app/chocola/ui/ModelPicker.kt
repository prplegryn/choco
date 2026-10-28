package app.chocola.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chocola.data.Provider

@Composable internal fun ModelChoices(models: List<Provider>, selected: String, modifier: Modifier = Modifier, onSelect: (Provider) -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        models.forEach { model ->
            Press(Modifier.fillMaxWidth().heightIn(min = 58.dp), radius = 12.dp, description = "选择 ${model.model}", onClick = { onSelect(model) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Txt(model.model, size = 14, weight = FontWeight.Medium, maxLines = 1)
                        val host = runCatching { java.net.URI(model.baseUrl).host }.getOrNull().orEmpty()
                        Txt(app.chocola.data.ModelAccounts.brand(model), size = 11, color = Palette.Secondary, maxLines = 1)
                    }
                    if (selected == model.id) Glyph("check", Modifier.size(17.dp)) else Spacer(Modifier.width(17.dp))
                }
            }
        }
        if (models.isEmpty()) Txt("尚未添加模型", Modifier.padding(14.dp), size = 13, color = Palette.Secondary)
    }
}

@Composable internal fun ModelPickerPanel(anchor: Rect, models: List<Provider>, selected: String, onDismiss: () -> Unit,
    onSelect: (Provider) -> Unit, onManage: () -> Unit) {
    AnchoredPanel(anchor, "选择模型", 310, 72 + models.size.coerceIn(1, 5) * 64, PanelGrowth.CONTEXT, onDismiss) { close ->
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            ModelChoices(models, selected, Modifier.weight(1f)) { model -> close.after { onSelect(model) } }
            Hairline(Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            Press(Modifier.fillMaxWidth().height(44.dp), radius = 12.dp, onClick = { close.after(onManage) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Glyph("sliders", Modifier.size(15.dp)); Txt("服务商设置", size = 12)
                }
            }
        }
    }
}

/** Content trails the moving edge by roughly one frame, with the original expansion duration. */
@Composable internal fun InlineMenu(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val ink by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f,
        tween(if (visible) Motion.Expand else 75, delayMillis = if (visible) RevealMotion.Lag else 0, easing = Motion.Travel), label = "inlineContent")
    AnimatedVisibility(visible, enter = expandVertically(tween(Motion.Expand, easing = Motion.Travel), expandFrom = Alignment.Top),
        exit = shrinkVertically(tween(Motion.Expand, easing = Motion.Travel), shrinkTowards = Alignment.Top)) {
        Column(Modifier.fillMaxWidth().background(Palette.Fill, RoundedCornerShape(14.dp))) {
            Column(Modifier.fillMaxWidth().padding(5.dp).menuInk { ink }, content = content)
        }
    }
}
