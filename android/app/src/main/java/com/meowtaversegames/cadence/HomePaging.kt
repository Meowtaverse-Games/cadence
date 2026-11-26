package com.meowtaversegames.cadence

import kotlin.math.abs

const val VIRTUAL_HOME_PAGES = Int.MAX_VALUE
fun realHomePage(virtualPage: Int, pageCount: Int): Int {
    require(pageCount > 0)
    return Math.floorMod(virtualPage, pageCount)
}
fun centeredHomePage(page: Int, pageCount: Int): Int {
    val middle = VIRTUAL_HOME_PAGES / 2
    return middle - realHomePage(middle, pageCount) + realHomePage(page, pageCount)
}
fun homePageId(virtualPage: Int, categoryIds: List<String>): String? =
    categoryIds.getOrNull(realHomePage(virtualPage, categoryIds.size + 1) - 1)
fun retainedHomePage(categoryId: String?, categoryIds: List<String>): Int =
    if (categoryId == null) 0 else categoryIds.indexOf(categoryId).let { if (it < 0) 0 else it + 1 }
fun nearestHomePage(current: Int, page: Int, pageCount: Int): Int {
    val delta = Math.floorMod(page - realHomePage(current, pageCount), pageCount)
    val forward = current.toLong() + delta
    val backward = forward - pageCount
    return listOf(forward, backward).filter { it in 0L until VIRTUAL_HOME_PAGES.toLong() }
        .minBy { abs(it - current) }.toInt()
}
data class PageMotion(val scale: Float = 1f, val alpha: Float = 1f)
fun homePageMotion(offset: Float, animatorScale: Float): PageMotion {
    if (animatorScale == 0f) return PageMotion()
    val distance = abs(offset).coerceIn(0f, 1f)
    return PageMotion(1f - 0.08f * distance, 1f - 0.4f * distance)
}
