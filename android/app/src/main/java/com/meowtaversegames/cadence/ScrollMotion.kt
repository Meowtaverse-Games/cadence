package com.meowtaversegames.cadence

/** X is a fraction of row width; Y uses the same units as the supplied layout coordinates. */
data class RowMotion(
    val translationXFraction: Float = 0f,
    val translationY: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val rotation: Float = 0f,
)

/** Position-only curve in the bottom 45% of the viewport. Reversing scroll retraces it exactly. */
fun calculateScrollMotion(itemTop: Float, itemHeight: Float, viewportStart: Float, viewportEnd: Float,
    strength: Float): RowMotion {
    if (listOf(itemTop, itemHeight, viewportStart, viewportEnd, strength).any { !it.isFinite() } ||
        itemHeight <= 0f || viewportEnd <= viewportStart || strength <= 0f) return RowMotion()
    // Double intermediates also keep extreme but finite layout inputs from overflowing.
    val viewport = viewportEnd.toDouble() - viewportStart
    val bandStart = viewportEnd - viewport * .45
    val depth = ((itemTop.toDouble() + itemHeight / 2.0 - bandStart) / (viewportEnd - bandStart)).coerceIn(0.0, 1.0)
    if (depth == 0.0) return RowMotion()
    val bend = depth * depth * (3 - 2 * depth) * strength.coerceAtMost(1f)
    return RowMotion(
        translationXFraction = (-.55 * bend).toFloat(),
        translationY = (minOf(itemHeight.toDouble(), viewport * .15) * .8 * bend * bend).toFloat(),
        scale = (1 - .12 * bend).toFloat(),
        alpha = (1 - .75 * bend).toFloat(),
        rotation = (-12 * bend).toFloat(),
    )
}
