package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class ScrollMotionTest {
    private fun motion(bottom: Float, strength: Float = 1f) = calculateScrollMotion(bottom - 80f, 80f, 0f, 1000f, strength)

    @Test fun rowsAboveTheBandAreUnchanged() {
        for (bottom in listOf(-100f, 0f, 400f, 700f)) assertEquals(RowMotion(), motion(bottom))
    }
    @Test fun bottomEdgeHasTheMaximumModestDeformation() {
        val bottom = motion(1000f)
        assertEquals(-.10f, bottom.translationXFraction, .0001f)
        assertEquals(14.4f, bottom.translationY, .0001f)
        assertEquals(.955f, bottom.scale, .0001f)
        assertEquals(.82f, bottom.alpha, .0001f)
        assertEquals(-3f, bottom.rotation, .0001f)
        assertEquals(bottom, motion(1200f))
    }
    @Test fun curveIsMonotoneAndReversingScrollRetracesIt() {
        val positions = (700..1000 step 5).map { it.toFloat() }
        val path = positions.map { motion(it) }
        path.zipWithNext().forEach { (upper, lower) ->
            assertTrue(upper.translationXFraction >= lower.translationXFraction)
            assertTrue(upper.translationY <= lower.translationY)
            assertTrue(upper.scale >= lower.scale)
            assertTrue(upper.alpha >= lower.alpha)
            assertTrue(upper.rotation >= lower.rotation)
        }
        assertEquals(path, positions.reversed().map { motion(it) }.reversed())
        // X and Y have different curvature, producing an arc rather than a straight diagonal.
        assertNotEquals(motion(850f).translationXFraction / motion(1000f).translationXFraction,
            motion(850f).translationY / motion(1000f).translationY)
    }
    @Test fun zeroStrengthIsIdentityAndPartialStrengthInterpolates() {
        for (bottom in listOf(0f, 700f, 850f, 1000f)) assertEquals(RowMotion(), motion(bottom, 0f))
        val full = motion(1000f); val half = motion(1000f, .5f)
        assertEquals(full.translationXFraction / 2, half.translationXFraction, .0001f)
        assertEquals(full.translationY / 2, half.translationY, .0001f)
        assertEquals((1 + full.scale) / 2, half.scale, .0001f)
        assertEquals((1 + full.alpha) / 2, half.alpha, .0001f)
        assertEquals(full.rotation / 2, half.rotation, .0001f)
        assertEquals(full, motion(1000f, 10f))
    }
    @Test fun coordinatesAreRelativeToTheViewportIncludingContentPadding() {
        assertEquals(motion(850f), calculateScrollMotion(750f, 80f, -20f, 980f, 1f))
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
