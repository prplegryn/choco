package app.chocola.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import app.chocola.R
import app.chocola.data.FontSizes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Ink = Color(0xFF222326)
    val Secondary = Color(0xFF65676E)
    val Faint = Color(0xFF91939A)
    val Border = Color(0xFFE4E5E8)
    val Fill = Color(0xFFF3F3F5)
    val Canvas = Color(0xFFFAFAF9)
    val Green = Color(0xFF367357)
    val Red = Color(0xFFAF4245)
}
val LocalAppFontScale = compositionLocalOf { FontSizes.Default }
private val LocalFloatingControls = compositionLocalOf { false }
@Composable fun Modifier.floatingSurface(radius: Dp = 24.dp, compact: Boolean = false, allowGlass: Boolean = true): Modifier {
    val shape = RoundedCornerShape(radius)
    val design = LocalFloatingDesign.current
    val backdrop = LocalControlBackdrop.current
    if (allowGlass && design.style == "glass" && backdrop != null && android.os.Build.VERSION.SDK_INT >= 31)
        return liquidSurface(backdrop, design, radius)
    return softShadow(radius, compact).background(Color.White, shape)
}
@Composable fun GroupLabel(text: String) { Txt(text, Modifier.padding(start = 12.dp, top = 18.dp, bottom = 7.dp), size = 12, color = Palette.Secondary, weight = FontWeight.Medium) }
@Composable fun ChocolaTheme(fontScale: Float = FontSizes.Default, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppFontScale provides fontScale,
        LocalTextSelectionColors provides TextSelectionColors(Palette.Ink, Color(0xFFDADDE5)), content = content)
}
@Composable fun Txt(text: String, modifier: Modifier = Modifier, size: Int = 16, color: Color = Palette.Ink,
    weight: FontWeight = FontWeight.Normal, maxLines: Int = Int.MAX_VALUE, mono: Boolean = false, lineHeight: Int = (size * 1.5).toInt()) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = (size * LocalAppFontScale.current).sp,
        lineHeight = (lineHeight * LocalAppFontScale.current).sp, fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif, platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
@Composable fun Press(modifier: Modifier = Modifier, enabled: Boolean = true, radius: Dp = 14.dp,
    description: String = "", onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (pressed && enabled) .975f else 1f, spring(dampingRatio = 1f, stiffness = 850f), label = "press")
    val opacity by animateFloatAsState(if (pressed && enabled) .76f else if (hovered && enabled) .86f else 1f, tween(if (pressed) 85 else 170), label = "pressInk")
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = opacity; clip = false; compositingStrategy = CompositingStrategy.ModulateAlpha }.then(modifier)
        .drawWithContent {
            drawContent()
            if (focused && enabled) drawRoundRect(Palette.Ink.copy(alpha = .45f), cornerRadius = CornerRadius(radius.toPx()), style = Stroke(1.25.dp.toPx()))
        }
        .semantics { if (description.isNotBlank()) contentDescription = description }
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center, content = content)
}
@Composable fun IconButton(icon: String, description: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.Ink,
    floating: Boolean = LocalFloatingControls.current, onClick: () -> Unit) {
    Press(modifier.size(48.dp), enabled, 24.dp, description, onClick) {
        if (floating) Box(Modifier.size(42.dp).floatingSurface(21.dp, compact = true), contentAlignment = Alignment.Center) {
            Glyph(icon, Modifier.size(21.dp), if (enabled) color else Palette.Faint)
        } else Glyph(icon, color = if (enabled) color else Palette.Faint)
    }
}
@Composable fun ActionButton(text: String, modifier: Modifier = Modifier, solid: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Press(modifier.heightIn(min = 48.dp), enabled, 14.dp, text, onClick) {
        Box(Modifier.height(42.dp).fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(if (solid) if (enabled) Palette.Ink else Color(0xFFB3B5BB) else Palette.Fill)
            .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Txt(text, size = 14, color = if (solid) Color.White else Palette.Ink, weight = FontWeight.Medium, maxLines = 1)
        }
    }
}
@Composable fun Hairline(modifier: Modifier = Modifier) { Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Border)) }
@Composable fun Entry(icon: String? = null, text: String, value: String = "", danger: Boolean = false, detail: String = "", onClick: () -> Unit) {
    Press(Modifier.fillMaxWidth().heightIn(min = 58.dp), radius = 12.dp, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Glyph(icon, color = if (danger) Palette.Red else Palette.Ink); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(text, color = if (danger) Palette.Red else Palette.Ink, maxLines = 1)
                if (detail.isNotBlank()) Txt(detail, size = 12, color = Palette.Secondary, maxLines = 2, lineHeight = 18)
            }
            if (value.isNotEmpty()) { Spacer(Modifier.width(12.dp)); Txt(value, Modifier.widthIn(max = 170.dp), size = 13, color = Palette.Secondary, maxLines = 1) }
            Spacer(Modifier.width(12.dp)); Glyph("right", Modifier.size(16.dp), color = Palette.Faint)
        }
    }
}
@Composable fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateFloatAsState(if (checked) 1f else 0f, spring(stiffness = 700f), label = "toggle")
    Press(Modifier.size(48.dp), radius = 24.dp, description = if (checked) "已开启" else "已关闭", onClick = { onChange(!checked) }) {
        Box(Modifier.size(38.dp, 23.dp).clip(CircleShape).background(if (checked) Palette.Ink else Color(0xFFCECFD4))) {
            Box(Modifier.padding(3.dp).size(17.dp).graphicsLayer { translationX = 15.dp.toPx() * x }.clip(CircleShape).background(Color.White))
        }
    }
}
@Composable fun ToggleEntry(text: String, checked: Boolean, detail: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(text)
            if (detail.isNotBlank()) Txt(detail, size = 12, color = Palette.Secondary, lineHeight = 18)
        }
        Toggle(checked, onChange)
    }
}
@Composable fun Segments(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val position by animateFloatAsState(selected.toFloat(), tween(Motion.Feedback, easing = Motion.Travel), label = "segment")
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Fill).padding(3.dp).drawBehind {
        if (items.isNotEmpty()) {
            val width = size.width / items.size
            drawRoundRect(Color.White, Offset(width * position, 0f), Size(width, size.height), CornerRadius(9.dp.toPx()))
        }
    }) {
        items.forEachIndexed { i, label ->
            Press(Modifier.weight(1f).height(36.dp), radius = 9.dp, onClick = { if (selected != i) { focus.clearFocus(force = true); keyboard?.hide(); onSelect(i) } }) {
                Txt(label, size = 14, weight = if (selected == i) FontWeight.Medium else FontWeight.Normal, color = if (selected == i) Palette.Ink else Palette.Secondary, maxLines = 1)
            }
        }
    }
}
@Composable fun Field(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", secret: Boolean = false, lines: Int = 1, keyboard: KeyboardType = KeyboardType.Text) {
    var revealed by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label.isNotBlank()) Txt(label, size = 13, color = Palette.Secondary, weight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Palette.Fill).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, onValue, modifier = Modifier.weight(1f).padding(vertical = 13.dp, horizontal = 0.dp), singleLine = lines == 1, maxLines = lines,
                textStyle = TextStyle(fontSize = (15 * LocalAppFontScale.current).sp, lineHeight = (22 * LocalAppFontScale.current).sp, color = Palette.Ink,
                    platformStyle = PlatformTextStyle(includeFontPadding = false), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)), cursorBrush = SolidColor(Palette.Ink),
                visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = !secret),
                decorationBox = { inner -> Box { if (value.isEmpty()) Txt(placeholder, size = 15, color = Palette.Faint); inner() } })
            if (secret) IconButton(if (revealed) "eye-off" else "eye", if (revealed) "隐藏内容" else "显示内容", onClick = { revealed = !revealed })
            else Spacer(Modifier.width(14.dp))
        }
    }
}
@Composable fun TopBar(onBack: (() -> Unit)? = null, center: @Composable RowScope.() -> Unit = {}, actions: @Composable RowScope.() -> Unit = {}) {
    CompositionLocalProvider(LocalFloatingControls provides true) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(68.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton("left", "返回", onClick = onBack)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = center)
            actions()
        }
    }
}

@Composable fun Glyph(name: String, modifier: Modifier = Modifier.size(22.dp), color: Color = Palette.Ink) {
    val resource = when (name) {
        "models" -> R.drawable.ic_models
        "notebook" -> R.drawable.ic_notebook
        "codex" -> R.drawable.ic_codex
        "redo" -> R.drawable.ic_redo
        "tone" -> R.drawable.ic_tone
        "display" -> R.drawable.ic_display
        "skills" -> R.drawable.ic_skills
        "mcp" -> R.drawable.ic_mcp
        "backup" -> R.drawable.ic_backup
        "font" -> R.drawable.ic_font
        "wallpaper" -> R.drawable.ic_wallpaper
        "image" -> R.drawable.ic_image
        "glass" -> R.drawable.ic_glass
        "indicator" -> R.drawable.ic_indicator
        "server" -> R.drawable.ic_server
        "wallet" -> R.drawable.ic_wallet
        "shield" -> R.drawable.ic_shield
        "menu" -> R.drawable.ic_menu
        "plus" -> R.drawable.ic_plus
        "new" -> R.drawable.ic_new
        "close" -> R.drawable.ic_close
        "left" -> R.drawable.ic_left
        "right" -> R.drawable.ic_right
        "down" -> R.drawable.ic_down
        "up" -> R.drawable.ic_up
        "send" -> R.drawable.ic_send
        "stop" -> R.drawable.ic_stop
        "more" -> R.drawable.ic_more
        "copy" -> R.drawable.ic_copy
        "check" -> R.drawable.ic_check
        "search" -> R.drawable.ic_search
        "folder" -> R.drawable.ic_folder
        "file" -> R.drawable.ic_file
        "terminal" -> R.drawable.ic_terminal
        "memory" -> R.drawable.ic_memory
        "settings" -> R.drawable.ic_settings
        "sliders" -> R.drawable.ic_sliders
        "refresh" -> R.drawable.ic_refresh
        "undo" -> R.drawable.ic_undo
        "edit" -> R.drawable.ic_edit
        "trash" -> R.drawable.ic_trash
        "lock" -> R.drawable.ic_lock
        "unlock" -> R.drawable.ic_unlock
        "key" -> R.drawable.ic_key
        "globe" -> R.drawable.ic_globe
        "attach" -> R.drawable.ic_attach
        "pin" -> R.drawable.ic_pin
        "share" -> R.drawable.ic_share
        "external" -> R.drawable.ic_external
        "download" -> R.drawable.ic_download
        "upload" -> R.drawable.ic_upload
        "play" -> R.drawable.ic_play
        "info" -> R.drawable.ic_info
        "background" -> R.drawable.ic_background
        "notification" -> R.drawable.ic_notification
        "battery" -> R.drawable.ic_battery
        "eye" -> R.drawable.ic_eye
        "eye-off" -> R.drawable.ic_eye_off
        "clock" -> R.drawable.ic_clock
        "repeat" -> R.drawable.ic_repeat
        "context" -> R.drawable.ic_context
        else -> R.drawable.ic_info
    }
    Image(painterResource(resource), contentDescription = null, modifier = modifier, colorFilter = ColorFilter.tint(color))
}
