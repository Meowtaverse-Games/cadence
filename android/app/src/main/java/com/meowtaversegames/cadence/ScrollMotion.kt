package com.meowtaversegames.cadence

/** Translations are fractions of row width and height, resolved only in graphicsLayer. */
data class RowMotion(
    val translationXFraction: Float = 0f,
    val translationYFraction: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val rotation: Float = 0f,
)

/** The caller supplies eased progress; non-finite progress must never corrupt a layer. */
fun entranceMotion(progress: Float): RowMotion {
    val p = if (progress.isNaN()) 1f else progress.coerceIn(0f, 1f)
    if (p == 1f) return RowMotion()
    val q = 1f - p
    return RowMotion(
        translationXFraction = -.5f * q,
        translationYFraction = .8f * q * q,
        scale = 1f - .12f * q,
        alpha = 1f - .75f * q,
        rotation = -12f * q,
    )
}

data class EntranceRow(val key: String, val offset: Int)

/** Null previous means the initial visibility baseline, never an entrance trigger. */
fun enteringKeys(previous: Set<String>?, current: List<EntranceRow>, scrolling: Boolean,
    enabled: Boolean, suppressed: Boolean, layoutChanged: Boolean): List<String> {
    if (previous == null || !scrolling || !enabled || suppressed || layoutChanged) return emptyList()
    return current.sortedBy { it.offset }.map { it.key }.filterNot { it in previous }
}

fun entranceDelayMillis(position: Int): Int = position.coerceIn(0, 6) * 25
