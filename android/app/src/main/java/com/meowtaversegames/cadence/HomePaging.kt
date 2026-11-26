package com.meowtaversegames.cadence

import kotlin.math.abs

const val VIRTUAL_HOME_PAGES = 100_000
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
/** The lower virtual coordinate wins ties, independently of drag direction. */
fun homePageContentOwner(page: Int, current: Int, offset: Float, pageCount: Int): Int {
    val real = realHomePage(page, pageCount)
    val position = current.toDouble() + offset
    val cycle = kotlin.math.floor((position - real) / pageCount).toLong()
    val lower = real + cycle * pageCount
    return listOf(lower, lower + pageCount).filter { it in 0L until VIRTUAL_HOME_PAGES.toLong() }
        .minWith(compareBy<Long> { abs(it - position) }.thenBy { it }).toInt()
}
fun ownsHomePageContent(virtualPage: Int, current: Int, offset: Float, pageCount: Int): Boolean =
    virtualPage == homePageContentOwner(virtualPage, current, offset, pageCount)

fun nearestHomePage(current: Int, page: Int, pageCount: Int): Int =
    homePageContentOwner(page, current, 0f, pageCount)

/** Only the labelled page indicator exposes the two custom paging actions. */
fun homePageIndicatorActionsEnabled(pageCount: Int, searching: Boolean, indexing: Boolean,
    searchFocused: Boolean): Boolean = pageCount > 1 && !searching && !indexing && !searchFocused

/** Keep the requested direction even with two pages; wrap at finite virtual edges. */
fun adjacentHomePage(current: Int, direction: Int, pageCount: Int): Int? {
    require(pageCount > 0)
    require(direction == -1 || direction == 1)
    if (pageCount == 1) return null
    val adjacent = current + direction
    return if (adjacent in 0 until VIRTUAL_HOME_PAGES) adjacent
    else nearestHomePage(current, realHomePage(adjacent, pageCount), pageCount)
}

/** HOME retains the current virtual coordinate when it already represents home. */
fun homePagerDestination(current: Int, previousIds: List<String>, nextIds: List<String>, homeRequested: Boolean): Int {
    if (nextIds.isEmpty()) return 0
    val page = if (homeRequested) 0 else retainedHomePage(homePageId(current, previousIds), nextIds)
    return nearestHomePage(current, page, nextIds.size + 1)
}

/** Only recenter at rest near an edge; preserve the real page and never interrupt a drag. */
fun recenteredHomePage(current: Int, pageCount: Int, scrolling: Boolean): Int? {
    if (scrolling || pageCount == 1) return null
    val margin = maxOf(100, pageCount)
    if (current >= margin && current < VIRTUAL_HOME_PAGES - margin) return null
    return centeredHomePage(realHomePage(current, pageCount), pageCount).takeIf { it != current }
}
data class PageMotion(val scale: Float = 1f, val alpha: Float = 1f)
fun homePageMotion(offset: Float, animatorScale: Float): PageMotion {
    if (animatorScale == 0f) return PageMotion()
    val distance = abs(offset).coerceIn(0f, 1f)
    return PageMotion(1f - 0.08f * distance, 1f - 0.4f * distance)
}
