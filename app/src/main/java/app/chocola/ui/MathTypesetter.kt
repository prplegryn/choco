package app.chocola.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import androidx.compose.ui.text.AnnotatedString
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import org.scilab.forge.jlatexmath.DefaultTeXFont
import org.scilab.forge.jlatexmath.AlphabetRegistration
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color
import ru.noties.jlatexmath.awt.Insets

/** Geometry is cached at one em size; Canvas scales vectors to the requested font size. */
internal class MathLayout(val icon: TeXIcon) {
    val width = icon.iconWidth.toFloat()
    val height = icon.iconHeight.toFloat()
    val baseline = height - icon.iconDepth
    private val graphics = AndroidGraphics2D()
    fun draw(canvas: Canvas, pixelsPerEm: Float, color: Int) {
        val saved = canvas.save()
        try {
            canvas.scale(pixelsPerEm / MathTypesetter.Em, pixelsPerEm / MathTypesetter.Em)
            graphics.setCanvas(canvas)
            icon.setForeground(Color(color))
            icon.paintIcon(null, graphics, 0, 0)
        } finally { canvas.restoreToCount(saved) }
    }
}
internal data class MathResult(val layout: MathLayout? = null, val failure: String = "")

internal object MathTypesetter {
    const val Em = 64f
    private val gate = Any()
    private val cacheGate = Any()
    private val cache = LinkedHashMap<Pair<String, Boolean>, MathResult>(32, .75f, true)
    private var characters = 0
    private var configured = false
    @Volatile private var initialized = false
    private var diagnostics: ((Map<String, Any>) -> Unit)? = null
    private val warmCanvas by lazy { Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
    fun initialize(context: Context, diagnostics: ((Map<String, Any>) -> Unit)? = null) {
        JLatexMathAndroid.init(context)
        this.diagnostics = diagnostics
        initialized = true
    }
    fun peek(expression: MathExpression): MathResult? = synchronized(cacheGate) { cache[expression.latex to expression.display] }
    /** Called only on a background dispatcher, before the prepared document is published. */
    fun render(expression: MathExpression): MathResult = synchronized(gate) {
        val key = expression.latex to expression.display
        peek(expression)?.let { return it }
        if (!initialized) return MathResult()
        val started = System.nanoTime()
        var reason = ""
        val result = if (!expression.complete || !MathSyntax.renderable(expression.latex)) MathResult() else try {
            if (!configured) {
                TeXFormula()
                // Upstream skips Greek registration when the optional Cyrillic package is absent.
                val greek = Class.forName("org.scilab.forge.jlatexmath.greek.GreekRegistration").getDeclaredConstructor().newInstance() as AlphabetRegistration
                DefaultTeXFont.registerAlphabet(greek)
                configured = true
            }
            val formula = TeXFormula(MathNormalizer.normalize(expression.latex))
            val icon = formula.TeXIconBuilder().setStyle(if (expression.display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT)
                .setSize(Em).setTrueValues(true).build()
            icon.setInsets(Insets(2, 2, 2, 2), true)
            val layout = MathLayout(icon)
            require(layout.width in 1f..131072f && layout.height in 1f..16384f && layout.baseline in 0f..layout.height) { "Formula dimensions" }
            // Load the exact typefaces before publishing, so first paint never opens font assets on UI.
            layout.draw(warmCanvas, Em, android.graphics.Color.BLACK)
            MathResult(layout)
        } catch (e: Exception) { reason = e.javaClass.simpleName; MathResult(failure = e.message.orEmpty()) }
        catch (_: StackOverflowError) { reason = "Nesting"; MathResult(failure = reason) }
        catch (e: LinkageError) { reason = e.javaClass.simpleName; MathResult(failure = e.message.orEmpty()) }
        diagnostics?.invoke(mapOf("chars" to expression.latex.length, "key" to expression.latex.hashCode().toUInt().toString(16),
            "display" to expression.display, "ok" to (result.layout != null), "reason" to reason, "ms" to (System.nanoTime() - started) / 1_000_000))
        if (expression.complete && expression.latex.length <= MathSyntax.MaxLength) {
            synchronized(cacheGate) {
                cache[key] = result; characters += expression.latex.length
                val oldest = cache.entries.iterator()
                while ((cache.size > 160 || characters > 96000) && oldest.hasNext()) { characters -= oldest.next().key.first.length; oldest.remove() }
            }
        }
        result
    }
    fun prepare(prepared: PreparedBlock) {
        if (!initialized) return
        prepared.block.math?.takeIf { it.complete }?.let(::render)
        (listOf(prepared.content) + prepared.cells.flatten()).forEach { content ->
            expressions(content, prepared.streaming).values.filter { it.complete }.forEach(::render)
        }
    }
    fun expressions(content: AnnotatedString, pending: Boolean): Map<String, MathExpression> = content.getStringAnnotations(MathSyntax.Tag, 0, content.length).mapNotNull { annotation ->
        MathSyntax.inlineAt(content.text.substring(annotation.start, annotation.end), 0, pending = pending)?.expression?.let { annotation.item to it }
    }.toMap()
}

/** Normalize document-only wrappers while retaining the exact original source for copying. */
internal object MathNormalizer {
    private val wrapper = Regex("""\\(begin|end)\{(equation\*?|align\*?|alignat\*?|gather\*?|split|multline\*?)\}""")
    private val label = Regex("""\\label\{[^{}]*\}|\\(?:nonumber|notag)\b""")
    private val tag = Regex("""\\tag(\*)?\{([^{}]*)\}""")
    fun normalize(source: String): String {
        var result = source.replace(wrapper) { match ->
            val env = when (match.groupValues[2]) {
                "equation", "equation*" -> ""
                "align", "align*", "split" -> "aligned"
                "alignat", "alignat*" -> "alignedat"
                "gather", "gather*" -> "gathered"
                else -> "multline"
            }
            if (env.isEmpty()) "" else "\\${match.groupValues[1]}{$env}"
        }.replace(label, "")
        result = result.replace(tag) { m -> "\\qquad\\text{" + if (m.groupValues[1].isEmpty()) "(${m.groupValues[2]})}" else "${m.groupValues[2]}}" }
        return result
    }
}
