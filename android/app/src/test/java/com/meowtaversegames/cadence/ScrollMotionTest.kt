package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class ScrollMotionTest {
    @Test fun zeroProgressStartsAtTheFullEntrancePose() {
        val start = entranceMotion(0f)
        assertEquals(-.5f, start.translationXFraction, .0001f)
        assertEquals(.8f, start.translationYFraction, .0001f)
        assertEquals(.88f, start.scale, .0001f)
        assertEquals(.25f, start.alpha, .0001f)
        assertEquals(-12f, start.rotation, .0001f)
    }
    @Test fun completedProgressIsExactlyIdentity() {
        assertEquals(RowMotion(), entranceMotion(1f))
    }
    @Test fun everyComponentSettlesMonotonically() {
        (0..100).map { entranceMotion(it / 100f) }.zipWithNext().forEach { (before, after) ->
            assertTrue(before.translationXFraction <= after.translationXFraction)
            assertTrue(before.translationYFraction >= after.translationYFraction)
            assertTrue(before.scale <= after.scale)
            assertTrue(before.alpha <= after.alpha)
            assertTrue(before.rotation <= after.rotation)
        }
    }
    @Test fun midpointFollowsAnArcRatherThanAStraightDiagonal() {
        val middle = entranceMotion(.5f)
        assertEquals(-.25f, middle.translationXFraction, .0001f)
        assertEquals(.2f, middle.translationYFraction, .0001f)
        assertEquals(.94f, middle.scale, .0001f)
        assertEquals(.625f, middle.alpha, .0001f)
        assertEquals(-6f, middle.rotation, .0001f)
    }
    @Test fun outOfRangeProgressClampsToTheEndpoints() {
        for (p in listOf(-.5f, -Float.MAX_VALUE, Float.NEGATIVE_INFINITY)) {
            assertEquals(entranceMotion(0f), entranceMotion(p))
        }
        for (p in listOf(1.5f, Float.MAX_VALUE, Float.POSITIVE_INFINITY)) {
            assertEquals(RowMotion(), entranceMotion(p))
        }
    }
    @Test fun invalidProgressNeverProducesNaNOrInfinity() {
        for (p in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -Float.MAX_VALUE, Float.MAX_VALUE)) {
            val motion = entranceMotion(p)
            assertTrue(listOf(motion.translationXFraction, motion.translationYFraction, motion.scale,
                motion.alpha, motion.rotation).all { it.isFinite() })
        }
        assertEquals(RowMotion(), entranceMotion(Float.NaN))
    }
}
