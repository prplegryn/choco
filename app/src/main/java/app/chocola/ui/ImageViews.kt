package app.chocola.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chocola.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Small decoded previews are shared across navigation. Source files remain full quality. */
private object ImagePreviews {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private fun key(image: Attachment, edge: Int) = "${image.path}:${image.size}:${image.revision}:$edge"
    fun cached(image: Attachment, edge: Int) = cache.get(key(image, edge))
    fun decode(image: Attachment, edge: Int, preview: Boolean): Bitmap {
        if (preview) cached(image, edge)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return ImageOrientation.apply(File(image.path), requireNotNull(BitmapFactory.decodeFile(image.path, options)) { "图片无法读取" }).also { if (preview) cache.put(key(image, edge), it) }
    }
}

@Composable private fun imageBitmap(image: Attachment, full: Boolean = false, edge: Int = 1024): Bitmap? {
    val logs = LocalDiagnostics.current
    val bitmap by produceState(if (full) null else ImagePreviews.cached(image, edge), image.path, image.size, image.revision, full, edge) {
        try { value = withContext(Dispatchers.IO) { ImagePreviews.decode(image, if (full) 2048 else edge, !full).also { ensureActive() } } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { logs?.error("image.preview.failed", error) }
    }
    return bitmap
}

private fun imageRatio(image: Attachment): Float = if (image.width > 0 && image.height > 0) image.width.toFloat() / image.height else 1f

/** Decoding and reveal never resize the allocated image slot. */
@Composable private fun Photo(image: Attachment, modifier: Modifier = Modifier, edge: Int = 1024) {
    val bitmap = imageBitmap(image, edge = edge)
    val reveal = animateFloatAsState(if (bitmap != null) 1f else 0f, tween(220, easing = Motion.Travel), label = "imageDecodeReveal")
    Box(modifier.background(Color(0xFFF1F1F3)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), image.name,
            Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }, contentScale = ContentScale.Crop)
    }
}

@Composable internal fun ComposerAttachments(attachments: List<Attachment>, onOpen: (Attachment) -> Unit, onRemove: (Attachment) -> Unit, onReference: (String) -> Unit) {
    val scroll = rememberScrollState()
    val labeled = attachments.count { it.mime.startsWith("image/") } > 1
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 2.dp)
        .clip(RoundedCornerShape(14.dp)).horizontalImageFades(scroll)) {
        Row(Modifier.horizontalScroll(scroll).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { image -> key(image.path) {
                val photo = image.mime.startsWith("image/")
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(if (photo) 66.dp else 154.dp, 66.dp)) {
                    Press(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)), radius = 14.dp,
                        description = if (photo) "预览${image.name}" else image.name, enabled = photo, onClick = { onOpen(image) }) {
                        if (photo) Photo(image, Modifier.fillMaxSize(), edge = 320)
                        else Row(Modifier.fillMaxSize().background(Palette.Fill).padding(start = 12.dp, end = 28.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Glyph("file", Modifier.size(18.dp), Palette.Secondary); Txt(image.name, size = 12, maxLines = 2)
                        }
                    }
                    Press(Modifier.align(Alignment.TopEnd).padding(4.dp).size(23.dp).background(Color.White, RoundedCornerShape(12.dp)),
                        radius = 12.dp, description = "移除${image.name}", onClick = { onRemove(image) }) { Glyph("close", Modifier.size(13.dp)) }
                }
                if (labeled) {
                    if (photo) Press(Modifier.width(66.dp).height(24.dp).background(Palette.Fill, RoundedCornerShape(9.dp)), radius = 9.dp,
                        description = "插入${image.imageLabel}", onClick = { onReference(image.imageLabel) }) { Txt(image.imageLabel, size = 11, color = Palette.Secondary, weight = FontWeight.Medium) }
                    else Spacer(Modifier.height(24.dp))
                }
                }
            } }
        }
    }
}

@Composable internal fun SentImages(images: List<Attachment>, onOpen: (Attachment) -> Unit) {
    val screen = LocalConfiguration.current.screenWidthDp.dp
    if (images.size == 1) {
        val image = images.single()
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Press(Modifier.width(screen * .4f).aspectRatio(imageRatio(image).coerceIn(.6f, 2.2f)).clip(RoundedCornerShape(17.dp)),
                radius = 17.dp, description = "查看${image.name}", onClick = { onOpen(image) }) { Photo(image, Modifier.fillMaxSize()) }
        }
    } else {
        val scroll = rememberScrollState()
        Box(Modifier.width(screen * .8f).clip(RoundedCornerShape(16.dp)).horizontalImageFades(scroll), contentAlignment = Alignment.TopEnd) {
            Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                images.forEach { image -> key(image.path) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Press(Modifier.height(112.dp).width(112.dp * imageRatio(image).coerceIn(.1f, 10f)).clip(RoundedCornerShape(15.dp)),
                        radius = 15.dp, description = "查看${image.name}", onClick = { onOpen(image) }) { Photo(image, Modifier.fillMaxSize(), edge = 768) }
                    }
                } }
            }
        }
    }
}

@Composable private fun GenerationPlaceholder(modifier: Modifier, phase: State<Float>, active: Boolean, main: Boolean = false) {
    Box(modifier.drawWithCache {
        onDrawBehind {
            drawRect(Color(0xFFF0F0F2))
            if (active) {
                val travel = phase.value * (size.width + size.height) * 2 - size.height
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .78f), Color.Transparent),
                    start = Offset(travel - size.width, 0f), end = Offset(travel, size.height)))
            }
        }
    }, contentAlignment = Alignment.Center) {
        if (main) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Glyph("image", Modifier.size(24.dp), Palette.Secondary)
            Txt(if (active) "生成中" else "未完成", size = 11, color = Palette.Secondary)
        } else Glyph("image", Modifier.size(18.dp), Palette.Secondary.copy(alpha = .6f))
    }
}

@Composable private fun ImageCaption(image: Attachment?, modifier: Modifier = Modifier) {
    if (image != null && image.width > 0 && image.height > 0) Column(modifier.padding(horizontal = 2.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Txt("${image.width} × ${image.height}", size = 10, color = LocalTranscriptSecondary.current)
        if (image.imageSizeMismatch) Txt("尺寸未匹配", size = 10, color = Palette.Red)
    }
}

@Composable internal fun ImageMessage(message: Message, onOpen: (Attachment) -> Unit) {
    val images = message.attachments.filter { it.mime.startsWith("image/") }
    val batch = message.imageBatch ?: images.takeIf { it.size > 1 && it.any { image -> image.output != null } }?.let { ImageBatch(it.size) }
    val active = message.status in setOf("running", "streaming")
    val phase: State<Float> = if (active && images.size < (batch?.count ?: 0)) {
        rememberInfiniteTransition(label = "imageGeneration").animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = LinearEasing)), label = "imageGenerationSweep")
    } else rememberUpdatedState(0f)
    val screen = LocalConfiguration.current.screenWidthDp.dp
    val user = message.role == "user"
    val multiBatch = maxOf(batch?.count ?: 0, images.size) > 1 && batch != null
    val inset = if (user) 20.dp else 22.dp
    Column(Modifier.fillMaxWidth().padding(start = if (multiBatch && !user) 0.dp else inset, end = if (multiBatch && user) 0.dp else inset).padding(top = 14.dp),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (batch != null) {
            val count = maxOf(batch.count, images.size)
            var selected by rememberSaveable(message.id) { mutableIntStateOf(0) }
            val index = selected.coerceIn(0, (count - 1).coerceAtLeast(0))
            val image = images.getOrNull(index)
            Column(Modifier.width(screen * if (count > 1) .6f else .4f)
                .animateContentSize(tween(240, easing = Motion.Travel), alignment = Alignment.TopStart),
                horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Press(Modifier.padding(start = if (multiBatch && !user) inset else 0.dp, end = if (multiBatch && user) inset else 0.dp)
                    .width(screen * .4f).aspectRatio((image?.let(::imageRatio) ?: batch.aspectRatio).coerceIn(.6f, 2.2f)).clip(RoundedCornerShape(17.dp)),
                    enabled = image != null, radius = 17.dp, description = "查看第${index + 1}张图片", onClick = { image?.let(onOpen) }) {
                    Crossfade(image, Modifier.fillMaxSize(), tween(240), label = "generatedImage") { current ->
                        if (current != null) Photo(current, Modifier.fillMaxSize())
                        else GenerationPlaceholder(Modifier.fillMaxSize(), phase, active, main = true)
                    }
                }
                if (count > 1) {
                    val scroll = rememberScrollState()
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).horizontalImageFades(scroll), contentAlignment = if (user) Alignment.TopEnd else Alignment.TopStart) {
                        Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            repeat(count) { i ->
                                val thumbnail = images.getOrNull(i)
                                Press(Modifier.size(54.dp).clip(RoundedCornerShape(11.dp)), radius = 11.dp, description = "第${i + 1}张图片",
                                    onClick = { selected = i }) {
                                    Crossfade(thumbnail, Modifier.fillMaxSize(), tween(220), label = "generatedThumbnail") { current ->
                                        if (current != null) Photo(current, Modifier.fillMaxSize(), edge = 256)
                                        else GenerationPlaceholder(Modifier.fillMaxSize(), phase, active)
                                    }
                                    if (i == index) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(13.dp, 3.dp).background(Color.White, RoundedCornerShape(2.dp)))
                                }
                            }
                        }
                    }
                }
                ImageCaption(image, Modifier.padding(start = if (multiBatch && !user) inset else 0.dp, end = if (multiBatch && user) inset else 0.dp))
            }
        } else images.forEach { image ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Press(Modifier.width(screen * .4f).aspectRatio(imageRatio(image).coerceIn(.6f, 2.2f)).clip(RoundedCornerShape(17.dp)),
                    radius = 17.dp, description = "查看${image.name}", onClick = { onOpen(image) }) { Photo(image, Modifier.fillMaxSize()) }
                ImageCaption(image)
            }
        }
    }
}

@Composable internal fun ImagePage(image: Attachment, repository: Repository, onBack: () -> Unit, onShare: (Attachment) -> Unit) {
    val scope = rememberCoroutineScope()
    val bitmap = imageBitmap(image, full = true)
    var zoom by remember(image.path) { mutableFloatStateOf(1f) }
    var offset by remember(image.path) { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 68.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
            val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
            val transform = rememberTransformableState { scale, pan, _ ->
                zoom = (zoom * scale).coerceIn(1f, 5f)
                val x = width * (zoom - 1) / 2; val y = height * (zoom - 1) / 2
                offset = (offset + pan).let { Offset(it.x.coerceIn(-x, x), it.y.coerceIn(-y, y)) }
            }
            if (bitmap != null) Image(bitmap.asImageBitmap(), "生成的图片", Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                .pointerInput(image.path) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; offset = Offset.Zero }) }
                .transformable(transform)
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
        }
        TopBar(onBack, actions = {
            IconButton("info", "图片信息") { details = true }
            IconButton("download", "保存图片", enabled = !saving) {
                if (!saving) scope.launch {
                    saving = true
                    try { saveImage(repository, image); repository.notice("已保存到相册") }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { repository.logs.error("image.export.failed", error); repository.notice("图片保存失败") }
                    finally { saving = false }
                }
            }
            IconButton("share", "分享图片") { onShare(image) }
        })
    }
    if (details) Sheet({ details = false }) {
        InfoPair("实际尺寸", if (image.width > 0 && image.height > 0) "${image.width} × ${image.height}" else "未记录")
        image.output?.let { output ->
            if (output.requestedSize.isNotBlank()) InfoPair("请求尺寸", output.requestedSize.replace("x", " × "))
            if (output.reportedSize.isNotBlank()) InfoPair("接口标注尺寸", output.reportedSize)
            if (output.requestedModel.isNotBlank()) InfoPair("请求模型", output.requestedModel)
            if (output.reportedModel.isNotBlank()) InfoPair("接口标注模型", output.reportedModel)
            if (output.requestedQuality.isNotBlank()) InfoPair("请求质量", output.requestedQuality)
            if (output.reportedQuality.isNotBlank()) InfoPair("接口标注质量", output.reportedQuality)
        }
        if (image.imageSizeMismatch) Txt(image.imageWarning, Modifier.padding(10.dp), size = 13, color = Palette.Red)
    }
}

private suspend fun saveImage(repository: Repository, image: Attachment) = withContext(Dispatchers.IO) {
    val resolver = repository.context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, image.name)
        put(MediaStore.Images.Media.MIME_TYPE, image.mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Choco")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    try {
        requireNotNull(resolver.openOutputStream(uri)).use { output -> File(image.path).inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) { ensureActive(); val count = input.read(bytes); if (count < 0) break; output.write(bytes, 0, count) }
        } }
        ensureActive()
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0)
    } catch (error: Exception) { resolver.delete(uri, null, null); throw error }
}
