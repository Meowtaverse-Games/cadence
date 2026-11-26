package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class HomePagingTest {
    @Test fun indicatorOffersBothPagingActionsOnlyWithMultiplePages() {
        for (count in 1..3) {
            assertEquals(count > 1, homePageIndicatorActionsEnabled(count,
                searching = false, indexing = false, searchFocused = false))
        }
    }
    @Test fun indicatorPagingActionsAreUnavailableDuringSearchIndexingOrSearchFocus() {
        for (count in 1..3) for (searching in listOf(false, true)) {
            for (indexing in listOf(false, true)) for (focused in listOf(false, true)) {
                if (searching || indexing || focused) {
                    assertFalse(homePageIndicatorActionsEnabled(count, searching, indexing, focused))
                }
            }
        }
    }
    @Test fun accessibilityActionsWrapFromHomeBackAndLastCategoryForward() {
        for (count in 2..5) {
            val home = centeredHomePage(0, count)
            val previous = requireNotNull(adjacentHomePage(home, -1, count))
            assertEquals(home - 1, previous)
            assertEquals(count - 1, realHomePage(previous, count))
            val last = home + count - 1
            val next = requireNotNull(adjacentHomePage(last, 1, count))
            assertEquals(last + 1, next)
            assertEquals(0, realHomePage(next, count))
            // With two pages the destinations have the same real page but opposite directions.
            assertEquals(home + 1, adjacentHomePage(home, 1, count))
        }
    }
    @Test fun accessibilityActionsStayWithinFiniteVirtualBounds() {
        for (count in 2..5) for (current in listOf(0, VIRTUAL_HOME_PAGES - 1)) {
            for (direction in listOf(-1, 1)) {
                val destination = requireNotNull(adjacentHomePage(current, direction, count))
                assertTrue(destination in 0 until VIRTUAL_HOME_PAGES)
                assertEquals(realHomePage(current + direction, count), realHomePage(destination, count))
            }
        }
    }
    @Test fun singlePageHasNoAccessibilityDestination() {
        assertNull(adjacentHomePage(0, -1, 1))
        assertNull(adjacentHomePage(0, 1, 1))
    }
    @Test fun cyclesInBothDirectionsIncludingNegativeCoordinates() {
        assertEquals(listOf(2, 0, 1, 2, 0, 1, 2), (-4..2).map { realHomePage(it, 3) })
        assertEquals(0, realHomePage(Int.MIN_VALUE, 1))
    }
    @Test fun centralStartingPointMapsToRequestedPageForEveryCount() {
        for (count in 1..100) for (page in 0 until count) {
            val virtual = centeredHomePage(page, count)
            assertEquals(page, realHomePage(virtual, count))
            assertTrue(virtual in 49_900..50_100)
        }
    }
    @Test fun changingCountAndOrderRetainsCategoryIdentity() {
        val previous = listOf("work", "docs", "other")
        val id = homePageId(centeredHomePage(2, 4), previous)
        assertEquals("docs", id)
        val next = listOf("new", "docs", "work")
        assertEquals(2, retainedHomePage(id, next))
        assertEquals(1, retainedHomePage(id, listOf("docs")))
        assertEquals(3, retainedHomePage(id, listOf("other", "work", "docs", "new")))
    }
    @Test fun deletionOfCurrentPageReturnsHomeAndHomeRemainsHome() {
        assertEquals(0, retainedHomePage("docs", listOf("work")))
        assertEquals(0, retainedHomePage("docs", emptyList()))
        assertEquals(0, retainedHomePage(null, listOf("work")))
        assertNull(homePageId(centeredHomePage(0, 2), listOf("work")))
    }
    @Test fun backUsesClosestHomeAcrossWrapAndStaysWithinVirtualBounds() {
        assertEquals(102, nearestHomePage(101, 0, 3))
        assertEquals(99, nearestHomePage(100, 0, 3))
        for (current in listOf(0, 1, VIRTUAL_HOME_PAGES - 2)) {
            val next = nearestHomePage(current, 0, 4)
            assertTrue(next >= 0 && next < VIRTUAL_HOME_PAGES)
            assertEquals(0, realHomePage(next, 4))
        }
    }
    @Test fun exactlyOneVirtualPageOwnsEachRealPageIncludingPrefetchAndFiniteEdges() {
        for (count in 1..3) {
            for (current in listOf(0, 1, 50_000, 50_001, VIRTUAL_HOME_PAGES - 1)) {
                for (offset in listOf(-0.5f, -0.25f, 0f, 0.25f, 0.5f)) {
                    val owners = (current - 6..current + 6).filter { it in 0 until VIRTUAL_HOME_PAGES }
                        .filter { ownsHomePageContent(it, current, offset, count) }
                    assertEquals(count, owners.size)
                    assertEquals((0 until count).toSet(), owners.map { realHomePage(it, count) }.toSet())
                }
            }
        }
    }
    @Test fun visiblePagesNeverBecomeEmptyDuringForwardReverseAndCancelledDrags() {
        for (count in 2..3) {
            // Includes both sides of the half-page currentPage change and a completed cycle.
            val positions = listOf(0.0, 0.01, 0.49, 0.5, 0.51, 0.99, 1.0, 1.01,
                1.49, 1.51, 2.0, 2.51, 3.0, 2.51, 1.01, 0.51, 0.49, 0.01, 0.0, -0.01, -0.51, -1.0, 0.0)
            for (relative in positions) {
                val position = 50_000 + relative
                val current = kotlin.math.floor(position + 0.5).toInt()
                val offset = (position - current).toFloat()
                for (virtual in current - 4..current + 4) {
                    if (kotlin.math.abs(virtual - position) < 1.0) {
                        assertTrue("count=$count position=$position visible=$virtual",
                            ownsHomePageContent(virtual, current, offset, count))
                    }
                }
            }
        }
    }
    @Test fun equidistantCopiesAlwaysGiveContentToTheLowerCoordinate() {
        for (count in 1..3) {
            val lower = 60_000
            val current = lower + count / 2
            val offset = if (count % 2 == 0) 0f else 0.5f
            assertTrue(ownsHomePageContent(lower, current, offset, count))
            assertFalse(ownsHomePageContent(lower + count, current, offset, count))
            assertEquals(lower, homePageContentOwner(lower, current, offset - 0.001f, count))
            assertEquals(lower + count, homePageContentOwner(lower, current, offset + 0.001f, count))
        }
    }
    @Test fun homeAfterFullCyclesKeepsTheSameVirtualPage() {
        for (ids in listOf(listOf("work"), listOf("work", "docs"))) {
            val count = ids.size + 1
            val initial = centeredHomePage(0, count)
            for (cycles in -3..3) {
                val current = initial + cycles * count
                assertEquals(current, homePagerDestination(current, ids, ids, homeRequested = true))
            }
        }
    }
    @Test fun homeFromACategoryUsesTheNearestStandardPage() {
        val ids = listOf("work", "docs")
        assertEquals(50_001, homePagerDestination(50_002, ids, ids, homeRequested = true))
        assertEquals(50_004, homePagerDestination(50_003, ids, ids, homeRequested = true))
        assertEquals(0, homePagerDestination(0, emptyList(), emptyList(), homeRequested = true))
    }
    @Test fun topologyChangesRetainCategoryIdentityNearTheCurrentCoordinate() {
        val previous = listOf("work", "docs")
        val current = centeredHomePage(2, 3)
        for (next in listOf(listOf("docs", "work"), listOf("new", "work", "docs"), listOf("docs"))) {
            val destination = homePagerDestination(current, previous, next, homeRequested = false)
            assertEquals("docs", homePageId(destination, next))
            assertTrue(kotlin.math.abs(destination - current) <= next.size + 1)
        }
    }
    @Test fun deletingTheCurrentCategoryReturnsToHomeIncludingTheLastCategory() {
        val previous = listOf("work", "docs")
        val current = centeredHomePage(2, 3)
        val destination = homePagerDestination(current, previous, listOf("work"), homeRequested = false)
        assertNull(homePageId(destination, listOf("work")))
        assertEquals(0, homePagerDestination(current, previous, emptyList(), homeRequested = false))
    }
    @Test fun finiteEdgesRecenterOnlyAtRestWithoutChangingTheRealPage() {
        assertEquals(100_000, VIRTUAL_HOME_PAGES)
        for (count in 2..3) for (page in listOf(0, 1, VIRTUAL_HOME_PAGES - 2, VIRTUAL_HOME_PAGES - 1)) {
            assertNull(recenteredHomePage(page, count, scrolling = true))
            val next = requireNotNull(recenteredHomePage(page, count, scrolling = false))
            assertEquals(realHomePage(page, count), realHomePage(next, count))
            assertTrue(next in 49_900..50_100)
            assertNull(recenteredHomePage(next, count, scrolling = false))
        }
        assertNull(recenteredHomePage(0, 1, scrolling = false))
    }
    @Test fun categoryBackIsConsumedRegardlessOfDefaultHome() {
        for (defaultHome in listOf(false, true)) {
            assertTrue(consumesHomeBack(false, defaultHome, true))
            assertTrue(consumesHomeBack(true, defaultHome, false))
        }
        assertFalse(consumesHomeBack(false, false, false))
        assertTrue(consumesHomeBack(false, true, false))
    }
    @Test fun pageMotionIsSymmetricClampedAndDisabledAtZeroAnimatorScale() {
        assertEquals(PageMotion(), homePageMotion(0f, 1f))
        assertEquals(PageMotion(0.92f, 0.6f), homePageMotion(1f, 1f))
        assertEquals(homePageMotion(1f, 1f), homePageMotion(-2f, 1f))
        assertEquals(0.96f, homePageMotion(-0.5f, 1f).scale, 0.0001f)
        assertEquals(0.8f, homePageMotion(0.5f, 1f).alpha, 0.0001f)
        assertEquals(PageMotion(), homePageMotion(1f, 0f))
    }
    @Test fun glassUsesOppositePlateAndFixedLightweightLayers() {
        assertEquals(0xFFFFFF, CategoryGlass.plateRgb(true))
        assertEquals(0x000000, CategoryGlass.plateRgb(false))
        assertEquals(0.28f, CategoryGlass.plateAlpha, 0f)
        assertEquals(0.10f, CategoryGlass.highlightAlpha, 0f)
        assertEquals(0.45f, CategoryGlass.borderTopAlpha, 0f)
        assertEquals(0.12f, CategoryGlass.borderBottomAlpha, 0f)
        assertEquals(0.04f, CategoryGlass.noiseAlpha, 0f)
        assertEquals(28, CategoryGlass.cornerDp)
        assertEquals(1, CategoryGlass.borderDp)
    }
}
