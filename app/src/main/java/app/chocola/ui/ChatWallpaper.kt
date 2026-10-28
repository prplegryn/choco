package app.chocola.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import app.chocola.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.*

internal val LocalTranscriptInk = compositionLocalOf { Palette.Ink }
internal val LocalTranscriptSecondary = compositionLocalOf { Palette.Secondary }
internal fun backgroundColor(hex: String) = Color(android.graphics.Color.parseColor(hex))

internal object WallpaperImages {
    private val cache = LruCache<String, Bitmap>(2)
    private val prepared = LruCache<String, Bitmap>(3)
    private val gate = Mutex()
    private fun key(d: ChatBackground) = "wallpaper-v2:${d.image}:${d.blur}"
    @Synchronized fun clear() { cache.evictAll(); prepared.evictAll() }
    fun peek(d: ChatBackground): Bitmap? = if (d.image.isBlank()) null else prepared.get(key(d))
    /** Blur is calculated once per image/radius. The saved render avoids repeating CPU blur at launch. */
    suspend fun prepare(context: Context, d: ChatBackground, persist: Boolean = false, logs: Diagnostics? = null): Bitmap? {
        val dispatched = System.nanoTime()
        return withContext(Dispatchers.IO) {
        logs?.timing("wallpaper.dispatch_wait", System.nanoTime() - dispatched)
        if (d.image.isBlank()) return@withContext null
        val span = logs?.begin("wallpaper", fields = mapOf("blur" to d.blur, "persist" to persist))
        var outcome = "failed"
        span?.stage("cache_lock")
        try { gate.withLock {
            val id = key(d)
            val dir = File(context.cacheDir, "wallpaper-renders")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
            val file = File(dir, "$digest.image")
            var source = "memory"
            val bitmap = prepared.get(id) ?: run {
                source = "disk"; span?.stage("disk_decode")
                (if (file.exists()) runCatching { decode(file.path) }.getOrNull() else null) ?: run {
                    source = "original"; span?.stage("decode_and_blur")
                    withContext(Dispatchers.Default) { blur(decode(d.image), d.blur) }
                }
            }
            prepared.put(id, bitmap)
            bitmap.prepareToDraw()
            if (persist && !file.exists()) {
                span?.stage("persist_render")
                dir.mkdirs()
                val temporary = File(dir, "$digest.part")
                runCatching {
                    temporary.outputStream().use { bitmap.compress(if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 94, it) }
                    if (!temporary.renameTo(file)) temporary.delete()
                    dir.listFiles()?.filter { it != file }?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.delete() }
                }.onFailure { temporary.delete() }
            }
            span?.event("wallpaper.ready", mapOf("source" to source, "widthPx" to bitmap.width, "heightPx" to bitmap.height,
                "allocationBytes" to bitmap.allocationByteCount, "elapsedMs" to (System.nanoTime() - dispatched) / 1_000_000.0))
            outcome = "done"
            bitmap
        } } catch (error: CancellationException) { outcome = "cancelled"; throw error }
        finally { span?.finish(outcome) }
    }
    }
    @Synchronized fun decode(path: String): Bitmap {
        cache.get(path)?.let { return it }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            val scale = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
        require(bitmap.width > 0 && bitmap.height > 0) { "无法读取图片" }
        cache.put(path, bitmap)
        return bitmap
    }
    fun import(context: Context, uri: Uri): Pair<String, String> {
        val dir = File(context.filesDir, "chat-backgrounds").apply { mkdirs() }
        val file = File(dir, "${newId()}.image")
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { out ->
                val bytes = ByteArray(16384); var total = 0
                while (true) { val n = input.read(bytes); if (n < 0) break; total += n; require(total <= 32_000_000) { "图片不能超过 32 MB" }; out.write(bytes, 0, n) }
            } }
            val bitmap = decode(file.absolutePath)
            var red = 0L; var green = 0L; var blue = 0L
            repeat(16) { y -> repeat(16) { x ->
                val c = bitmap.getPixel((bitmap.width - 1) * x / 15, (bitmap.height - 1) * y / 15)
                red += android.graphics.Color.red(c); green += android.graphics.Color.green(c); blue += android.graphics.Color.blue(c)
            } }
            val tone = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", red / 256, green / 256, blue / 256)
            return file.absolutePath to tone
        } catch (e: Exception) { file.delete(); throw e }
    }
    /** Bounded CPU blur also works on Android 10/11; only wallpaper pixels enter this worker. */
    suspend fun blur(source: Bitmap, radius: Float): Bitmap {
        if (radius < .5f) return source
        val scale = minOf(1f, 600f / maxOf(source.width, source.height))
        val small = Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt().coerceAtLeast(1), (source.height * scale).roundToInt().coerceAtLeast(1), true)
        val w = small.width; val h = small.height
        var pixels = IntArray(w * h); small.getPixels(pixels, 0, w, 0, 0, w, h)
        val r = (radius * maxOf(w, h) / 900f).roundToInt().coerceIn(1, 36)
        repeat(2) {
            pixels = boxPass(pixels, w, h, r, true)
            pixels = boxPass(pixels, w, h, r, false)
        }
        if (small !== source) small.recycle()
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
    private suspend fun boxPass(input: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
        val result = IntArray(input.size)
        val lines = if (horizontal) h else w; val length = if (horizontal) w else h
        val count = r * 2 + 1
        for (line in 0 until lines) {
            currentCoroutineContext().ensureActive()
            fun at(position: Int): Int = if (horizontal) line * w + position.coerceIn(0, length - 1) else position.coerceIn(0, length - 1) * w + line
            var red = 0; var green = 0; var blue = 0
            for (i in -r..r) { val c = input[at(i)]; red += (c shr 16) and 255; green += (c shr 8) and 255; blue += c and 255 }
            for (i in 0 until length) {
                result[at(i)] = (255 shl 24) or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
                val old = input[at(i - r)]; val next = input[at(i + r + 1)]
                red += ((next shr 16) and 255) - ((old shr 16) and 255)
                green += ((next shr 8) and 255) - ((old shr 8) and 255)
                blue += (next and 255) - (old and 255)
            }
        }
        return result
    }
}

internal fun wallpaperFilter(d: ChatBackground): ColorMatrix {
    val s = d.saturation; val c = d.contrast
    val red = .213f * (1 - s); val green = .715f * (1 - s); val blue = .072f * (1 - s)
    val offset = 255 * (d.brightness + (1 - c) * .5f)
    return ColorMatrix(floatArrayOf(
        (red + s) * c, green * c, blue * c, 0f, offset + d.warmth * 38,
        red * c, (green + s) * c, blue * c, 0f, offset,
        red * c, green * c, (blue + s) * c, 0f, offset - d.warmth * 38,
        0f, 0f, 0f, 1f, 0f))
}
private fun transformed(hex: String, d: ChatBackground): Color {
    val source = backgroundColor(hex); val matrix = wallpaperFilter(d).values
    val rgb = floatArrayOf(source.red * 255, source.green * 255, source.blue * 255)
    fun channel(row: Int): Float = ((rgb[0] * matrix[row] + rgb[1] * matrix[row + 1] + rgb[2] * matrix[row + 2] + matrix[row + 4]) / 255).coerceIn(0f, 1f)
    return Color(channel(0), channel(5), channel(10))
}
internal fun wallpaperTone(d: ChatBackground): Color {
    val base = if (d.image.isNotBlank()) transformed(d.imageTone, d) else lerp(transformed(d.color, d), transformed(d.colorEnd, d), .5f)
    return lerp(base, backgroundColor(d.veilColor), d.veil)
}
internal fun wallpaperLightText(d: ChatBackground): Boolean = when (d.text) { "light" -> true; "dark" -> false; else -> wallpaperTone(d).luminance() < .35f }
internal fun wallpaperEdge(d: ChatBackground): Color = if (wallpaperLightText(d)) lerp(wallpaperTone(d), Color.Black, .12f) else lerp(wallpaperTone(d), Color.White, .16f)

@Composable internal fun ChatWallpaper(design: ChatBackground, modifier: Modifier = Modifier) {
    val d = remember(design) { design.normalized() }
    val context = LocalContext.current.applicationContext
    val logs = LocalDiagnostics.current
    val blurred by produceState(WallpaperImages.peek(d), d.image, d.blur) {
        try { value = WallpaperImages.prepare(context, d, logs = logs) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { value = null }
    }
    val image = remember(blurred) { blurred?.asImageBitmap() }
    val filter = remember(d.brightness, d.contrast, d.saturation, d.warmth) { ColorFilter.colorMatrix(wallpaperFilter(d)) }
    val first = transformed(d.color, d); val last = transformed(d.colorEnd, d)
    Canvas(modifier.fillMaxSize()) {
        val radians = d.angle * PI / 180
        val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
        val extent = abs(size.width * direction.x) / 2 + abs(size.height * direction.y) / 2
        drawRect(Brush.linearGradient(listOf(first, last), center - direction * extent.coerceAtLeast(1f), center + direction * extent.coerceAtLeast(1f)))
        image?.let { bitmap ->
            val crop = BackgroundGeometry.crop(bitmap.width.toFloat(), bitmap.height.toFloat(), size.width, size.height, d.horizontal, d.zoom)
            drawImage(bitmap, dstOffset = IntOffset(crop.left.roundToInt(), crop.top.roundToInt()), dstSize = IntSize(crop.width.roundToInt().coerceAtLeast(1), crop.height.roundToInt().coerceAtLeast(1)), colorFilter = filter, filterQuality = FilterQuality.Medium)
        }
        if (d.veil > 0) drawRect(backgroundColor(d.veilColor).copy(alpha = d.veil))
    }
}
