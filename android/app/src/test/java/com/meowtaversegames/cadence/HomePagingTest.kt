package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class HomePagingTest {
    @Test fun cyclesInBothDirectionsIncludingNegativeCoordinates() {
        assertEquals(listOf(2, 0, 1, 2, 0, 1, 2), (-4..2).map { realHomePage(it, 3) })
        assertEquals(0, realHomePage(Int.MIN_VALUE, 1))
    }
    @Test fun centralStartingPointMapsToRequestedPageForEveryCount() {
        for (count in 1..100) for (page in 0 until count) {
            val virtual = centeredHomePage(page, count)
            assertEquals(page, realHomePage(virtual, count))
            assertTrue(virtual in 1_000_000_000..1_100_000_000)
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
        for (current in listOf(0, 1, Int.MAX_VALUE - 2)) {
            val next = nearestHomePage(current, 0, 4)
            assertTrue(next >= 0 && next < VIRTUAL_HOME_PAGES)
            assertEquals(0, realHomePage(next, 4))
        }
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
        assertEquals(0.28f, CategoryGlass.plateAlpha)
        assertEquals(0.10f, CategoryGlass.highlightAlpha)
        assertEquals(0.45f, CategoryGlass.borderTopAlpha)
        assertEquals(0.12f, CategoryGlass.borderBottomAlpha)
        assertEquals(0.04f, CategoryGlass.noiseAlpha)
        assertEquals(28, CategoryGlass.cornerDp)
        assertEquals(1, CategoryGlass.borderDp)
    }
}
