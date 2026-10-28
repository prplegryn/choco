package app.chocola

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import app.chocola.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Numerical parser/metrics/raster checks on the JVM. No Activity, emulator or UI screenshots. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MathTypesetterTest {
    @Before fun initialize() { MathTypesetter.initialize(RuntimeEnvironment.getApplication()) }
    private fun layout(latex: String, display: Boolean = true): MathLayout {
        val result = MathTypesetter.render(MathExpression(latex, latex, display))
        assertNotNull("Formula: $latex, ${result.failure}", result.layout)
        return requireNotNull(result.layout)
    }
    @Test fun fractionsRootsAndNestedScriptsHaveValidGeometry() {
        listOf("\\frac{a+b}{c+d}", "\\dfrac{1}{1+\\frac{1}{x}}", "x_i^{2}+\\sqrt[3]{y}", "\\binom{n}{k}").forEach {
            val math = layout(it)
            assertTrue(math.width > 4f && math.height > 4f)
            assertTrue(math.baseline > 0f && math.baseline <= math.height)
        }
    }
    @Test fun sumsIntegralsLimitsAndGreekLettersRenderInBothStyles() {
        listOf("\\sum_{n=1}^{\\infty}\\frac{1}{n^2}=\\frac{\\pi^2}{6}",
            "\\int_0^\\infty e^{-x^2}\\,dx=\\frac{\\sqrt{\\pi}}{2}",
            "\\lim_{x\\to0}\\frac{\\sin x}{x}=1", "\\alpha+\\beta+\\Gamma+\\Omega+\\varepsilon+\\theta", "α+β=Γ", "\\text{αβγ}").forEach {
            layout(it, false); layout(it, true)
        }
        assertTrue(layout("\\sum_{i=1}^{n}x_i").height > layout("\\sum_{i=1}^{n}x_i", false).height)
    }
    @Test fun matricesDeterminantsAndChinesePiecewiseSystemsRender() {
        listOf("\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}",
            "\\begin{bmatrix}1&0&0\\\\0&1&0\\\\0&0&1\\end{bmatrix}",
            "\\begin{vmatrix}a&b\\\\c&d\\end{vmatrix}=ad-bc",
            "f(x)=\\begin{cases}x^2,&\\text{当 }x\\ge0\\\\-x,&\\text{当 }x<0\\end{cases}",
            "\\left\\{\\begin{aligned}2x+y&=5\\\\x-y&=1\\end{aligned}\\right.").forEach { layout(it) }
    }
    @Test fun complexMultilineDerivationsArraysAndNumberedEquationsRender() {
        listOf("\\begin{aligned}\\nabla\\cdot\\mathbf{E}&=\\frac{\\rho}{\\varepsilon_0}\\\\\\nabla\\times\\mathbf{B}&=\\mu_0\\mathbf{J}+\\mu_0\\varepsilon_0\\frac{\\partial\\mathbf{E}}{\\partial t}\\end{aligned}",
            "\\begin{align*}(a+b)^2&=a^2+2ab+b^2\\\\&\\ge4ab\\end{align*}",
            "\\begin{gather}a=b+c\\\\d=e+f\\end{gather}",
            "\\begin{equation}E=mc^2\\label{eq:energy}\\tag{1}\\end{equation}",
            "\\begin{array}{rcl}x&=&1\\\\y&=&\\frac{2}{3}\\end{array}").forEach { layout(it) }
    }
    @Test fun nativePaintingProducesInkAndRespectsRequestedTextColor() {
        val math = layout("\\frac{x^2+1}{y}")
        val bitmap = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
        try {
            math.draw(Canvas(bitmap), 40f, android.graphics.Color.rgb(17, 35, 52))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.count { android.graphics.Color.alpha(it) > 0 } > 10)
            assertTrue(pixels.any { android.graphics.Color.alpha(it) > 240 && android.graphics.Color.blue(it) > android.graphics.Color.red(it) })
        } finally { bitmap.recycle() }
    }
    @Test fun invalidFormulasReturnAFallbackAndDoNotPoisonTheNextFormula() {
        listOf("\\notARealCommand{x}", "\\frac{a}{", "\\includegraphics{file}").forEach {
            assertNull(MathTypesetter.render(MathExpression(it, it, true)).layout)
        }
        val math = layout("x^2+y^2=z^2")
        assertSame(math, layout("x^2+y^2=z^2"))
    }
}
