package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class EnteringKeysTest {
    private val a = EntranceRow("a", 0)
    private val b = EntranceRow("b", 80)
    private val c = EntranceRow("c", 160)

    @Test fun firstDisplayOnlyEstablishesTheBaselineEvenIfScrolling() {
        assertTrue(enteringKeys(null, listOf(a, b), true, true, false, false).isEmpty())
    }
    @Test fun oneRowEnteringFromBelowIsSelected() {
        assertEquals(listOf("c"), enteringKeys(setOf("a", "b"), listOf(b, c), true, true, false, false))
    }
    @Test fun oneRowEnteringFromAboveIsSelected() {
        assertEquals(listOf("a"), enteringKeys(setOf("b", "c"), listOf(a, b), true, true, false, false))
    }
    @Test fun simultaneousEntrancesAreOrderedFromTopToBottom() {
        assertEquals(listOf("a", "c"), enteringKeys(setOf("b"), listOf(c, b, a), true, true, false, false))
    }
    @Test fun idleChangesDoNotAnimate() {
        assertTrue(enteringKeys(setOf("a"), listOf(b, c), false, true, false, false).isEmpty())
    }
    @Test fun suppressedJumpsAndSearchChangesDoNotAnimate() {
        assertTrue(enteringKeys(setOf("a"), listOf(b, c), true, true, true, false).isEmpty())
    }
    @Test fun rebuildingTheListDoesNotAnimateEvenDuringAFling() {
        assertTrue(enteringKeys(setOf("a"), listOf(b, c), true, true, false, true).isEmpty())
    }
    @Test fun disabledMotionDoesNotAnimate() {
        assertTrue(enteringKeys(setOf("a"), listOf(b, c), true, false, false, false).isEmpty())
    }
    @Test fun eachReturningKeyCanEnterAgainAfterLeaving() {
        var previous = setOf("a", "b")
        val outside = listOf(b, c)
        assertEquals(listOf("c"), enteringKeys(previous, outside, true, true, false, false))
        previous = outside.map { it.key }.toSet()
        assertEquals(listOf("a"), enteringKeys(previous, listOf(a, b), true, true, false, false))
        assertEquals(listOf("a", "b"), enteringKeys(emptySet(), listOf(a, b), true, true, false, false))
    }
    @Test fun staggerAdds25MillisecondsPerRowAndCapsAt150() {
        assertEquals(listOf(0, 25, 50, 75, 100, 125, 150, 150, 150), (0..8).map(::entranceDelayMillis))
        assertEquals(0, entranceDelayMillis(-1))
        assertEquals(150, entranceDelayMillis(Int.MAX_VALUE))
    }
}
