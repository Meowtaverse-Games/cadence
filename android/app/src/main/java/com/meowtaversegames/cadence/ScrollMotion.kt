package com.meowtaversegames.cadence

/** X is a fraction of row width; Y uses the same units as the supplied layout coordinates. */
data class RowMotion(
    val translationXFraction: Float = 0f,
    val translationY: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val rotation: Float = 0f,
)

/** Position-only curve in the bottom 30% of the viewport. Reversing scroll retraces it exactly. */
fun calculateScrollMotion(itemTop: Float, itemHeight: Float, viewportStart: Float, viewportEnd: Float,
    strength: Float): RowMotion {
    if (listOf(itemTop, itemHeight, viewportStart, viewportEnd, strength).any { !it.isFinite() } ||
        itemHeight <= 0f || viewportEnd <= viewportStart || strength <= 0f) return RowMotion()
    // Double intermediates also keep extreme but finite layout inputs from overflowing.
    val viewport = viewportEnd.toDouble() - viewportStart
    val bandStart = viewportEnd - viewport * .30
    val depth = ((itemTop.toDouble() + itemHeight - bandStart) / (viewport * .30)).coerceIn(0.0, 1.0)
    if (depth == 0.0) return RowMotion()
    val bend = depth * depth * strength.coerceAtMost(1f)
    return RowMotion(
        translationXFraction = (-.10 * bend).toFloat(),
        translationY = (minOf(itemHeight.toDouble(), viewport * .15) * .18 * depth * bend).toFloat(),
        scale = (1 - .045 * bend).toFloat(),
        alpha = (1 - .18 * bend).toFloat(),
        rotation = (-3 * bend).toFloat(),
    )
}
