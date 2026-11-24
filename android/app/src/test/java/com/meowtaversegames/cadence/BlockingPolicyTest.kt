package com.meowtaversegames.cadence

import com.meowtaversegames.cadence.core.BlockInterval
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BlockingPolicyTest {
    private fun time(seconds: Long) = Instant.ofEpochSecond(seconds)
    private fun interval(start: Long, end: Long) = BlockInterval(time(start), time(end))
    private val targets = setOf("synthetic.game")

    @Test fun insideIntervalRunsUntilEnd() {
        assertEquals(BlockingDecision(true, time(30), time(30)), decideBlocking(time(20), listOf(interval(10, 30)), targets))
    }
    @Test fun outsideIntervalSchedulesStart() {
        assertEquals(BlockingDecision(false, null, time(10)), decideBlocking(time(0), listOf(interval(10, 30)), targets))
    }
    @Test fun startIsInclusive() { assertTrue(decideBlocking(time(10), listOf(interval(10, 30)), targets).runService) }
    @Test fun endIsExclusive() { assertEquals(BlockingDecision(false, null, null), decideBlocking(time(30), listOf(interval(10, 30)), targets)) }
    @Test fun adjacentIntervalsDoNotStopAtSharedBoundary() {
        val intervals = listOf(interval(10, 30), interval(30, 50))
        assertEquals(time(50), decideBlocking(time(20), intervals, targets).end)
        assertEquals(BlockingDecision(true, time(50), time(50)), decideBlocking(time(30), intervals, targets))
    }
    @Test fun noTimetableStopsAndCancels() { assertEquals(BlockingDecision(false, null, null), decideBlocking(time(20), emptyList(), targets)) }
    @Test fun emptyTargetsDoNotRunServiceButRetainSchedule() {
        assertEquals(BlockingDecision(false, time(30), time(30)), decideBlocking(time(20), listOf(interval(10, 30)), emptySet()))
    }
    @Test fun gapSchedulesNextStart() {
        assertEquals(BlockingDecision(false, null, time(40)), decideBlocking(time(30), listOf(interval(10, 30), interval(40, 60)), targets))
    }
}
