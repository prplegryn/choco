package app.chocola.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.LruCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A cached nine-slice shadow can resize without rerunning a blur on every animation frame. */
internal object SoftShadows {
    private data class Key(val radius: Int, val blur: Int, val offset: Int)
    internal class Tile(val bitmap: Bitmap, val inset: Int, val corner: Int, val middle: Int) {
        val sources: List<Rect> = run {
            val cut = intArrayOf(0, inset + corner, inset + corner + middle, bitmap.width)
            List(9) { index -> val x = index % 3; val y = index / 3; Rect(cut[x], cut[y], cut[x + 1], cut[y + 1]) }
        }
    }
    private val cache = object : LruCache<Key, Tile>(4 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Tile) = value.bitmap.allocationByteCount
    }
    fun warm(density: Float) {
        tile(21 * density, 20 * density, 2 * density)
        tile(16 * density, 20 * density, 2 * density)
        tile(28 * density, 28 * density, 3 * density)
        tile(22 * density, 20 * density, 2 * density)
    }
    fun tile(radius: Float, blur: Float, offset: Float): Tile {
        val key = Key(radius.roundToInt().coerceAtLeast(1), blur.roundToInt().coerceAtLeast(1), offset.roundToInt())
        cache.get(key)?.let { return it }
        val inset = ceil(key.blur * 1.8f + kotlin.math.abs(key.offset)).toInt() + 2
        val middle = 2
        val side = inset * 2 + key.radius * 2 + middle
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = RectF(inset.toFloat(), inset.toFloat() + key.offset, (side - inset).toFloat(), (side - inset).toFloat() + key.offset)
        paint.color = 0x800B0C10.toInt()
        paint.maskFilter = BlurMaskFilter(key.blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(bounds, key.radius.toFloat(), key.radius.toFloat(), paint)
        paint.color = 0x300B0C10
        paint.maskFilter = BlurMaskFilter((key.blur * .22f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        bounds.offset(0f, -key.offset * .65f)
        canvas.drawRoundRect(bounds, key.radius.toFloat(), key.radius.toFloat(), paint)
        return Tile(bitmap, inset, key.radius, middle).also { cache.put(key, it) }
    }
}

internal fun Modifier.softShadow(radius: Dp, compact: Boolean = false, blur: Dp = if (compact) 20.dp else 28.dp,
    offset: Dp = if (compact) 2.dp else 3.dp, opacity: Float = .08f, color: Color = Color(0xFF0B0C10)): Modifier = if (opacity <= 0f) this else drawWithCache {
    val corner = minOf(radius.toPx(), size.width / 2, size.height / 2)
    val tile = SoftShadows.tile(corner, blur.toPx(), offset.toPx())
    val x = floatArrayOf(-tile.inset.toFloat(), corner, size.width - corner, size.width + tile.inset)
    val y = floatArrayOf(-tile.inset.toFloat(), corner, size.height - corner, size.height + tile.inset)
    val destinations = List(9) { index -> val col = index % 3; val row = index / 3; RectF(x[col], y[row], x[col + 1], y[row + 1]) }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (opacity.coerceIn(0f, .5f) * 510).roundToInt()
        colorFilter = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_IN)
    }
    onDrawBehind {
        drawIntoCanvas { canvas ->
            for (index in 0..8) {
                val destination = destinations[index]
                if (destination.width() > 0 && destination.height() > 0) canvas.nativeCanvas.drawBitmap(tile.bitmap, tile.sources[index], destination, paint)
            }
        }
    }
}
