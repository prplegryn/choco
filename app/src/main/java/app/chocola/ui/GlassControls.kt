package app.chocola.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch

internal val LocalControlBackdrop = compositionLocalOf<Backdrop?> { null }
internal val LocalFloatingDesign = compositionLocalOf { FloatingDesign() }

/** Official Backdrop effect pipeline, matching its glass tutorial: vibrancy → blur → lens. */
internal fun Modifier.liquidSurface(backdrop: Backdrop, design: FloatingDesign, radius: Dp): Modifier = drawBackdrop(
    backdrop = backdrop, shape = { RoundedCornerShape(radius) },
    effects = {
        vibrancy()
        blur(design.blur.dp.toPx())
        val height = minOf(radius.toPx(), design.refraction.dp.toPx(), size.minDimension / 2)
        lens(height, minOf(height * 2, size.minDimension), depthEffect = true)
    }, onDrawSurface = { drawRect(Color.White.copy(alpha = design.white)) })

@Composable internal fun FloatingControlsPage(state: AppState, repository: Repository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var design by remember { mutableStateOf(state.preferences.floating.normalized()) }
    val clipboard = LocalClipboardManager.current
    SettingsScaffold(onBack, actions = { IconButton("check", "保存控件样式") { scope.launch {
            repository.preferences(repository.state.value.preferences.copy(floating = design.normalized())); onBack()
        } } }) {
            item {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxWidth().height(166.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Fill, RoundedCornerShape(24.dp))) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        ChatWallpaper(state.preferences.chatBackground.copy(image = state.preferences.chatBackground.image,
                            color = "#B9C6D7", colorEnd = "#F3DBCC"))
                    }
                    CompositionLocalProvider(LocalControlBackdrop provides backdrop, LocalFloatingDesign provides design) {
                        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton("menu", "预览菜单", floating = true) { }
                            Press(Modifier.height(44.dp).floatingSurface(22.dp, compact = true).padding(horizontal = 18.dp), radius = 22.dp, onClick = {}) { Txt("新会话", size = 13) }
                            IconButton("settings", "预览设置", floating = true) { }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
            item { Segments(listOf("柔和白色", "液态玻璃"), if (design.style == "glass") 1 else 0, { design = design.copy(style = if (it == 1) "glass" else "white") }, Modifier.fillMaxWidth()) }
            if (design.style == "glass") {
                item { SettingSlider("背景模糊", design.blur, 0f..16f, "${design.blur.toInt()}") { design = design.copy(blur = it) } }
                item { SettingSlider("边缘折射", design.refraction, 4f..28f, "${design.refraction.toInt()}") { design = design.copy(refraction = it) } }
                item { SettingSlider("表面白度", design.white, .08f.. .8f, "${(design.white * 100).toInt()}%") { design = design.copy(white = it) } }
                if (Build.VERSION.SDK_INT < 33) item { Txt(if (Build.VERSION.SDK_INT < 31) "此系统版本使用白色表面" else "此系统版本支持模糊，折射需要 Android 13", size = 12, color = Palette.Secondary) }
            }
            item { Entry("copy", "复制当前配置") { clipboard.setText(AnnotatedString(org.json.JSONObject().put("schema", "chocola.floating.v1").put("configuration", design.normalized().json()).toString(2))) } }
    }
}
