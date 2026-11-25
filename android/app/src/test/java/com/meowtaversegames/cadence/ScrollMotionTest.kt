package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class ScrollMotionTest {
    private fun motion(center: Float, strength: Float = 1f) = calculateScrollMotion(center - 40f, 80f, 0f, 1000f, strength)

    @Test fun rowsCenteredAtOrAboveTheBandAreUnchanged() {
        for (center in listOf(-100f, 0f, 400f, 550f)) assertEquals(RowMotion(), motion(center))
    }
    @Test fun rowCenteredAtBottomEdgeHasTheMaximumDeformation() {
        val bottom = motion(1000f)
        assertEquals(-.55f, bottom.translationXFraction, .0001f)
        assertEquals(64f, bottom.translationY, .0001f)
        assertEquals(.88f, bottom.scale, .0001f)
        assertEquals(.25f, bottom.alpha, .0001f)
        assertEquals(-12f, bottom.rotation, .0001f)
        assertEquals(bottom, motion(1200f))
    }
    @Test fun middleOfTheBandHasVisibleMotion() {
        val middle = motion(775f)
        assertTrue(middle.translationXFraction <= -.25f)
        assertEquals(-.275f, middle.translationXFraction, .0001f)
        assertEquals(16f, middle.translationY, .0001f)
        assertEquals(.94f, middle.scale, .0001f)
        assertEquals(.625f, middle.alpha, .0001f)
        assertEquals(-6f, middle.rotation, .0001f)
        // At quarter depth smoothstep is 0.15625, easing the row into its resting position.
        assertEquals(-.0859375f, motion(662.5f).translationXFraction, .0001f)
    }
    @Test fun curveIsMonotoneAndReversingScrollRetracesIt() {
        val positions = (550..1000 step 5).map { it.toFloat() }
        val path = positions.map { motion(it) }
        path.zipWithNext().forEach { (upper, lower) ->
            assertTrue(upper.translationXFraction >= lower.translationXFraction)
            assertTrue(upper.translationY <= lower.translationY)
            assertTrue(upper.scale >= lower.scale)
            assertTrue(upper.alpha >= lower.alpha)
            assertTrue(upper.rotation >= lower.rotation)
        }
        assertEquals(path, positions.reversed().map { motion(it) }.reversed())
        // X is proportional to e and Y to e squared, producing an arc.
        assertNotEquals(motion(775f).translationXFraction / motion(1000f).translationXFraction,
            motion(775f).translationY / motion(1000f).translationY)
    }
    @Test fun zeroStrengthIsIdentityAndPartialStrengthFollowsTheArc() {
        for (center in listOf(0f, 550f, 775f, 1000f)) assertEquals(RowMotion(), motion(center, 0f))
        val full = motion(1000f); val half = motion(1000f, .5f)
        assertEquals(full.translationXFraction / 2, half.translationXFraction, .0001f)
        assertEquals(full.translationY / 4, half.translationY, .0001f)
        assertEquals((1 + full.scale) / 2, half.scale, .0001f)
        assertEquals((1 + full.alpha) / 2, half.alpha, .0001f)
        assertEquals(full.rotation / 2, half.rotation, .0001f)
        assertEquals(full, motion(1000f, 10f))
    }
    @Test fun tallRowsKeepVerticalDisplacementBoundedByViewport() {
        val tall = calculateScrollMotion(600f, 800f, 0f, 1000f, 1f)
        assertEquals(120f, tall.translationY, .0001f)
    }
    @Test fun coordinatesAreRelativeToTheViewportIncludingContentPadding() {
        assertEquals(motion(775f), calculateScrollMotion(715f, 80f, -20f, 980f, 1f))
    }
    @Test fun invalidAndExtremeInputsNeverProduceNaN() {
        val inputs = listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE)
        for (value in inputs) {
            val samples = listOf(calculateScrollMotion(value, 80f, 0f, 1000f, 1f),
                calculateScrollMotion(900f, value, 0f, 1000f, 1f), calculateScrollMotion(900f, 80f, 0f, value, 1f),
                calculateScrollMotion(900f, 80f, value, 1000f, 1f), calculateScrollMotion(900f, 80f, 0f, 1000f, value))
            samples.forEach { result ->
                assertTrue(listOf(result.translationXFraction, result.translationY, result.scale,
                    result.alpha, result.rotation).all { it.isFinite() })
            }
        }
        assertEquals(RowMotion(), calculateScrollMotion(900f, 80f, 0f, 0f, 1f))
        assertEquals(RowMotion(), calculateScrollMotion(900f, 80f, 1000f, 0f, 1f))
        assertEquals(RowMotion(), motion(1000f, -1f))
    }
}
