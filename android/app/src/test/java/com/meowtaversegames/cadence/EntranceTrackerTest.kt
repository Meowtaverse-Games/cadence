package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class EntranceTrackerTest {
    private val keys = (0..200).map { "app-$it" }
    private fun frame(vararg visible: Int, scrolling: Boolean = true, enabled: Boolean = true,
        gate: EntranceSuppression = EntranceSuppression(suppressed = false),
        order: List<String> = keys, measurement: Long = 1) = EntranceFrame(
        visible.mapIndexed { index, id -> EntranceRow(keys[id], index * 80) }, order, scrolling,
        enabled, gate.suppressed, gate.generation, measurement)

    @Test fun homeAfterAnEntranceBaselinesThenRestartsOnTheNextScroll() {
        val tracker = EntranceTracker()
        var gate = EntranceSuppression(suppressed = false)
        assertTrue(tracker.observe(frame(0, 1, scrolling = false, gate = gate)).clearAll)
        assertEquals(listOf(keys[2]), tracker.observe(frame(1, 2, gate = gate, measurement = 2)).start)
        gate = gate.request()
        assertTrue(tracker.observe(frame(1, 2, gate = gate, measurement = 2)).clearAll)
        assertTrue(tracker.activeKeys.isEmpty())
        assertTrue(tracker.observe(frame(0, 1, scrolling = false, gate = gate, measurement = 3)).start.isEmpty())
        gate = gate.afterIdleFrame(gate.generation, false)
        tracker.observe(frame(0, 1, scrolling = false, gate = gate, measurement = 3))
        assertEquals(listOf(keys[2]), tracker.observe(frame(1, 2, gate = gate, measurement = 4)).start)
    }

    @Test fun fiveHomeReturnsNeverDisableLaterEntrances() {
        val tracker = EntranceTracker()
        var gate = EntranceSuppression(suppressed = false)
        tracker.observe(frame(0, 1, scrolling = false, gate = gate, measurement = 0))
        repeat(5) { cycle ->
            val m = cycle * 3L
            assertEquals(listOf(keys[2]), tracker.observe(frame(1, 2, gate = gate, measurement = m + 1)).start)
            gate = gate.request()
            assertTrue(tracker.observe(frame(0, 1, scrolling = false, gate = gate, measurement = m + 2)).start.isEmpty())
            gate = gate.afterIdleFrame(gate.generation, false)
            tracker.observe(frame(0, 1, scrolling = false, gate = gate, measurement = m + 2))
        }
        assertEquals(listOf(keys[2]), tracker.observe(frame(1, 2, gate = gate, measurement = 16)).start)
    }

    @Test fun searchInputClearAndIndexJumpsAllRecover() {
        val tracker = EntranceTracker()
        var gate = EntranceSuppression(suppressed = false)
        tracker.observe(frame(0, 1, scrolling = false))
        val orders = listOf(keys.drop(10), keys, keys)
        val starts = listOf(10, 0, 50)
        orders.zip(starts).forEachIndexed { index, (order, start) ->
            gate = gate.request()
            val m = index * 3L + 2
            val jump = tracker.observe(frame(start, start + 1, scrolling = false, gate = gate, order = order, measurement = m))
            assertTrue(jump.clearAll)
            assertTrue(jump.start.isEmpty())
            gate = gate.afterIdleFrame(gate.generation, false)
            tracker.observe(frame(start, start + 1, scrolling = false, gate = gate, order = order, measurement = m))
            assertEquals(listOf(keys[start + 2]), tracker.observe(frame(start + 1, start + 2,
                gate = gate, order = order, measurement = m + 1)).start)
        }
    }

    @Test fun alreadyIdleJumpReleasesEvenWithoutANewMeasurement() {
        val tracker = EntranceTracker()
        var gate = EntranceSuppression(suppressed = false)
        tracker.observe(frame(0, 1, scrolling = false, gate = gate))
        gate = gate.request()
        tracker.observe(frame(0, 1, scrolling = false, gate = gate))
        gate = gate.afterIdleFrame(gate.generation, false)
        assertFalse(gate.suppressed)
        tracker.observe(frame(0, 1, scrolling = false, gate = gate))
        assertEquals(listOf(keys[2]), tracker.observe(frame(1, 2, gate = gate, measurement = 2)).start)
    }

    @Test fun repeatedSuppressionCannotBeReleasedByAnOldWatcherAndEventuallyReleases() {
        val first = EntranceSuppression().request()
        val next = first.request()
        assertEquals(next, next.afterIdleFrame(first.generation, false))
        assertEquals(next, next.afterIdleFrame(next.generation, true))
        assertFalse(next.afterIdleFrame(next.generation, false).suppressed)
    }

    @Test fun changedLabelsAndNewListInstancesDoNotResetVisibility() {
        val tracker = EntranceTracker()
        val original = keys.map { HomeItem.App(AppEntry(it.removePrefix("app-"), "synthetic.app", "Original", 1, "A")) }
        val renamed = original.map { it.copy(entry = it.entry.copy(label = "Updated")) }
        assertNotEquals(original, renamed)
        tracker.observe(frame(0, 1, order = original.map { it.key }))
        val result = tracker.observe(frame(1, 2, order = renamed.map { it.key }, measurement = 2))
        assertFalse(result.clearAll)
        assertEquals(listOf(keys[2]), result.start)
    }

    @Test fun changedKeyOrderBaselinesOnlyTheRebuildThenResumes() {
        val tracker = EntranceTracker()
        tracker.observe(frame(0, 1))
        val reordered = listOf("favorite-new") + keys
        assertTrue(tracker.observe(frame(1, 2, order = reordered, measurement = 2)).start.isEmpty())
        assertEquals(listOf(keys[3]), tracker.observe(frame(2, 3, order = reordered, measurement = 3)).start)
    }

    @Test fun providerBeforeMeasurementWaitsOnceThenAlwaysRecovers() {
        val tracker = EntranceTracker()
        tracker.observe(frame(0, 1))
        val reordered = keys.reversed()
        assertTrue(tracker.observe(frame(0, 1, order = reordered)).clearAll)
        assertTrue(tracker.observe(frame(0, 1, order = reordered)).start.isEmpty())
        assertTrue(tracker.observe(frame(3, 4, order = reordered, measurement = 2)).start.isEmpty())
        assertEquals(listOf(keys[5]), tracker.observe(frame(4, 5, order = reordered, measurement = 3)).start)
        assertEquals(listOf(keys[6]), tracker.observe(frame(5, 6, order = reordered, measurement = 4)).start)
    }

    @Test fun independentPagesResumeScrollEntrancesAfterRepeatedPagingAndHomeReturns() {
        val home = EntranceTracker()
        val category = EntranceTracker()
        var homeGate = EntranceSuppression(suppressed = false)
        home.observe(frame(0, 1, scrolling = false))
        category.observe(frame(10, 11, scrolling = false, enabled = false))
        repeat(6) { cycle ->
            val m = cycle * 10L + 2
            home.observe(frame(0, 1, scrolling = false, enabled = false, gate = homeGate, measurement = m))
            // Horizontal arrival changes visibility but is not a list scroll.
            assertTrue(category.observe(frame(10, 11, scrolling = false, measurement = m)).start.isEmpty())
            assertEquals(listOf(keys[12]), category.observe(frame(11, 12, measurement = m + 1)).start)
            category.observe(frame(11, 12, scrolling = false, enabled = false, measurement = m + 2))
            homeGate = homeGate.request()
            home.observe(frame(0, 1, scrolling = false, gate = homeGate, measurement = m + 3))
            homeGate = homeGate.afterIdleFrame(homeGate.generation, false)
            home.observe(frame(0, 1, scrolling = false, gate = homeGate, measurement = m + 3))
            assertEquals(listOf(keys[2]), home.observe(frame(1, 2, gate = homeGate, measurement = m + 4)).start)
            // Return to the same category, then scroll upward: a returning row animates again.
            assertTrue(category.observe(frame(11, 12, scrolling = false, measurement = m + 5)).start.isEmpty())
            assertEquals(listOf(keys[10]), category.observe(frame(10, 11, measurement = m + 6)).start)
        }
    }

    private fun disableAndRestore() {
        val tracker = EntranceTracker()
        tracker.observe(frame(0, 1))
        tracker.observe(frame(1, 2, measurement = 2))
        assertTrue(tracker.activeKeys.isNotEmpty())
        assertTrue(tracker.observe(frame(2, 3, enabled = false, measurement = 3)).clearAll)
        assertTrue(tracker.activeKeys.isEmpty())
        tracker.observe(frame(3, 4, enabled = false, measurement = 4))
        assertTrue(tracker.observe(frame(3, 4, measurement = 4)).start.isEmpty())
        assertEquals(listOf(keys[5]), tracker.observe(frame(4, 5, measurement = 5)).start)
    }

    @Test fun themeNoneThenLowerLeftRestartsEntrances() = disableAndRestore()
    @Test fun animatorScaleZeroThenOneRestartsEntrances() = disableAndRestore()

    @Test fun longFlingStartsEachRowAndCompletionDiscardsOnlyFinishedState() {
        val tracker = EntranceTracker()
        tracker.observe(frame(0, 1, 2))
        for (i in 1..150) {
            assertEquals(listOf(keys[i + 2]), tracker.observe(frame(i, i + 1, i + 2, measurement = i + 1L)).start)
            assertTrue(tracker.activeKeys.contains(keys[i + 2]))
            tracker.completed(keys[i + 2])
            assertTrue(tracker.activeKeys.isEmpty())
        }
        assertEquals(listOf(keys[149]), tracker.observe(frame(149, 150, 151, measurement = 153)).start)
    }

    @Test fun leavingViewportRemovesUnfinishedRowsAndAllowsTheirReturn() {
        val tracker = EntranceTracker()
        tracker.observe(frame(0, 1))
        tracker.observe(frame(1, 2, measurement = 2))
        val leaving = tracker.observe(frame(3, 4, measurement = 3))
        assertEquals(setOf(keys[2]), leaving.remove)
        assertEquals(setOf(keys[3], keys[4]), tracker.activeKeys)
        val returning = tracker.observe(frame(1, 2, measurement = 4))
        assertEquals(listOf(keys[1], keys[2]), returning.start)
        assertEquals(setOf(keys[3], keys[4]), returning.remove)
    }
    private fun assertFirstFrame(tracker: EntranceTracker, frame: EntranceFrame, key: String, progress: Float) {
        val prediction = tracker.preview(frame)
        assertEquals(progress, if (key in prediction.start) 0f else 1f, 0f)
        // Drawing more than once must not consume the transition.
        assertEquals(prediction, tracker.preview(frame))
        assertEquals(prediction, tracker.observe(frame))
    }

    @Test fun firstDrawStartsAtZeroOnScrollAndOnReturnAfterCompletion() {
        val tracker = EntranceTracker()
        assertFirstFrame(tracker, frame(0, 1, scrolling = false), keys[1], 1f)
        assertFirstFrame(tracker, frame(1, 2, measurement = 2), keys[2], 0f)
        tracker.completed(keys[2])
        assertFirstFrame(tracker, frame(2, 3, measurement = 3), keys[2], 1f)
        assertFirstFrame(tracker, frame(3, 4, measurement = 4), keys[4], 0f)
        assertFirstFrame(tracker, frame(2, 3, measurement = 5), keys[2], 0f)
    }

    @Test fun firstDrawDuringJumpsAndImmediatelyAfterReleaseIsIdentity() {
        val tracker = EntranceTracker()
        var gate = EntranceSuppression(suppressed = false)
        tracker.observe(frame(0, 1, scrolling = false))
        // HOME, index and search input/clear each establish a new generation.
        for (start in listOf(0, 50, 10, 0)) {
            gate = gate.request()
            assertFirstFrame(tracker, frame(start, start + 1, gate = gate), keys[start + 1], 1f)
            gate = gate.afterIdleFrame(gate.generation, false)
            // Even if scrolling resumed before the collector saw the release, baseline once.
            assertFirstFrame(tracker, frame(start + 1, start + 2, gate = gate, measurement = 2), keys[start + 2], 1f)
            assertFirstFrame(tracker, frame(start + 2, start + 3, gate = gate, measurement = 3), keys[start + 3], 0f)
        }
    }

    @Test fun firstDrawOfRebuildAndItsDelayedMeasurementIsIdentity() {
        val tracker = EntranceTracker()
        tracker.observe(frame(0, 1))
        val order = keys.reversed()
        assertFirstFrame(tracker, frame(0, 1, order = order), keys[1], 1f)
        assertFirstFrame(tracker, frame(10, 11, order = order, measurement = 2), keys[11], 1f)
        assertFirstFrame(tracker, frame(11, 12, order = order, measurement = 3), keys[12], 0f)
    }

    @Test fun firstDrawWhileDisabledAndOnPageArrivalNeverHidesRows() {
        val tracker = EntranceTracker()
        assertFirstFrame(tracker, frame(0, 1), keys[1], 1f)
        repeat(3) { // Theme none, animator scale zero, and horizontal paging use the same gate.
            assertFirstFrame(tracker, frame(10, 11, enabled = false), keys[11], 1f)
            assertFirstFrame(tracker, frame(11, 12), keys[12], 1f)
            assertFirstFrame(tracker, frame(12, 13, measurement = 2), keys[13], 0f)
        }
        assertFirstFrame(tracker, frame(20, 21, scrolling = false, measurement = 3), keys[21], 1f)
        assertFirstFrame(tracker, frame(21, 22, measurement = 4), keys[22], 0f)
    }

}
