package app.chocola.ui

internal data class InlineMathBox(val width: Float, val height: Float, val top: Float)

/** A TextCenter placeholder can still align the mathematical baseline exactly with surrounding text. */
internal fun inlineMathBox(width: Float, height: Float, baseline: Float, textCenterOffset: Float): InlineMathBox {
    val half = maxOf(baseline + textCenterOffset, height - baseline - textCenterOffset, 1f)
    return InlineMathBox(width.coerceAtLeast(1f), half * 2f, half - textCenterOffset - baseline)
}
