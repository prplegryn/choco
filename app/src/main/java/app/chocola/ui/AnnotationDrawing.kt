package app.chocola.ui

import android.graphics.*
import android.media.ExifInterface
import app.chocola.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.*

internal object ImageOrientation {
    private fun orientation(file: File) = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    fun dimensions(file: File, raw: Pair<Int, Int>): Pair<Int, Int> = if (orientation(file) in 5..8) raw.second to raw.first else raw
    fun apply(file: File, source: Bitmap): Bitmap {
        val matrix = Matrix()
        when (orientation(file)) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(270f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also { if (it !== source) source.recycle() }
    }
}

/** Compile paths once per edit; panning and zooming only change the canvas transform. */
internal class AnnotationDrawing(marks: List<AnnotationMark>, private val width: Float, private val height: Float) {
    private val commands: List<(Canvas) -> Unit> = marks.mapNotNull { mark ->
        if (mark.points.isEmpty()) return@mapNotNull null
        val base = minOf(width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mark.color.toInt(); style = Paint.Style.STROKE
            strokeWidth = (mark.width * base).coerceAtLeast(.7f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            if (mark.tool == "highlight") { alpha = 82; strokeWidth *= 4 }
        }
        val start = mark.points.first().let { PointF(it.x * width, it.y * height) }
        val end = mark.points.last().let { PointF(it.x * width, it.y * height) }
        when (mark.tool) {
            "text" -> {
                paint.style = Paint.Style.FILL; paint.textSize = mark.textSize * base
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val lines = mark.text.lines()
                val draw: (Canvas) -> Unit = { canvas -> lines.forEachIndexed { index, line -> canvas.drawText(line, start.x, start.y + paint.textSize * (index * 1.24f + .8f), paint) } }
                draw
            }
            "rectangle", "ellipse" -> {
                val rect = RectF(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
                val draw: (Canvas) -> Unit = { canvas -> if (mark.tool == "ellipse") canvas.drawOval(rect, paint) else canvas.drawRoundRect(rect, base * .005f, base * .005f, paint) }
                draw
            }
            else -> {
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    if (mark.tool == "arrow") {
                        lineTo(end.x, end.y)
                        val angle = atan2(end.y - start.y, end.x - start.x)
                        val head = maxOf(base * .025f, paint.strokeWidth * 3.5f)
                        moveTo(end.x - cos(angle - .55f) * head, end.y - sin(angle - .55f) * head); lineTo(end.x, end.y)
                        lineTo(end.x - cos(angle + .55f) * head, end.y - sin(angle + .55f) * head)
                    } else {
                        mark.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
                        if (mark.points.size == 1) lineTo(start.x + .01f, start.y)
                    }
                }
                val draw: (Canvas) -> Unit = { it.drawPath(path, paint) }
                draw
            }
        }
    }
    fun draw(canvas: Canvas) { commands.forEach { it(canvas) } }
}

internal object AnnotationRenderer {
    suspend fun preview(image: Attachment): Bitmap = withContext(Dispatchers.IO) { decode(File(image.originalPath.ifBlank { image.path }), 2048, mutable = false) }
    private fun decode(file: File, edge: Int, mutable: Boolean): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法读取" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge || bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 16_000_000) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inMutable = mutable; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return ImageOrientation.apply(file, requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "图片无法读取" })
    }
    suspend fun export(image: Attachment, history: AnnotationHistory): Attachment = withContext(Dispatchers.IO) {
        val original = File(image.originalPath.ifBlank { image.path })
        val marks = history.marks()
        if (marks.isEmpty()) {
            val mime = image.originalMime.ifBlank { image.mime }
            val dimensions = ImageOrientation.dimensions(original, ImageDimensions.read(original, mime))
            return@withContext image.copy(name = original.name, path = original.path, mime = mime, width = dimensions.first, height = dimensions.second,
                size = original.length(), originalPath = "", originalMime = "", revision = image.revision + 1)
        }
        var bitmap = decode(original, 6000, mutable = true)
        if (!bitmap.isMutable) {
            val previous = bitmap
            bitmap = requireNotNull(previous.copy(Bitmap.Config.ARGB_8888, true)) { "无法准备标注画布" }
            previous.recycle()
        }
        val target = File(original.parentFile, "${original.nameWithoutExtension.take(100)}-marked-${image.draftId.take(8)}.png")
        val temporary = File(target.path + ".part")
        try {
            AnnotationDrawing(marks, bitmap.width.toFloat(), bitmap.height.toFloat()).draw(Canvas(bitmap))
            FileOutputStream(temporary).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            image.copy(name = target.name, path = target.path, mime = "image/png", size = target.length(), width = bitmap.width, height = bitmap.height,
                originalPath = original.path, originalMime = image.originalMime.ifBlank { image.mime }, revision = image.revision + 1)
        } finally { bitmap.recycle(); temporary.delete() }
    }
}
