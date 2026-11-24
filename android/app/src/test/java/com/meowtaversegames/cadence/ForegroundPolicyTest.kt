package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class ForegroundPolicyTest {
    private val game = ActivityKey("synthetic.game", "Game")
    private val other = ActivityKey("synthetic.other", "Other")
    private val cadence = ActivityKey("synthetic.cadence", "Block")
    private val targets = setOf(game.packageName)
    private fun event(time: Long, type: ActivityEventType, key: ActivityKey = game) = ActivityEvent(time, key, type)
    private fun reduce(events: List<ActivityEvent>, block: BlockWindow = BlockWindow(), now: Long = 100) =
        reduceForeground(ForegroundState(), events, now, targets, block)

    @Test fun sameClassNewInstanceIgnoresOldStopped() {
        val result = reduce(listOf(event(1, ActivityEventType.RESUMED), event(2, ActivityEventType.PAUSED),
            event(3, ActivityEventType.RESUMED), event(4, ActivityEventType.STOPPED)))
        assertEquals(targets, result.packages)
        assertEquals(ActivityEventType.RESUMED, result.state.activities.getValue(game).last.type)
    }
    @Test fun stoppedAfterPausedRemainsAbsent() {
        assertTrue(reduce(listOf(event(1, ActivityEventType.RESUMED), event(2, ActivityEventType.PAUSED), event(3, ActivityEventType.STOPPED))).packages.isEmpty())
    }
    @Test fun crashWithoutPauseIsCleanedWhenBlockIsFullscreen() {
        assertTrue(reduce(listOf(event(1, ActivityEventType.RESUMED)), BlockWindow(true, false)).packages.isEmpty())
    }
    @Test fun fullscreenCleanupDoesNotClearUnrelatedPackages() {
        assertEquals(setOf(other.packageName), reduce(listOf(event(1, ActivityEventType.RESUMED), event(2, ActivityEventType.RESUMED, other)), BlockWindow(true, false)).packages)
    }
    @Test fun splitScreenRetainsTargetEvenWithCadenceResumed() {
        assertEquals(setOf(game.packageName, cadence.packageName), reduce(listOf(event(1, ActivityEventType.RESUMED),
            event(2, ActivityEventType.RESUMED, cadence)), BlockWindow(true, true)).packages)
    }
    @Test fun splitScreenBecomingFullscreenCleansStaleTarget() {
        val split = reduce(listOf(event(1, ActivityEventType.RESUMED)), BlockWindow(true, true))
        assertTrue(reduceForeground(split.state, emptyList(), 101, targets, BlockWindow(true, false)).packages.isEmpty())
    }
    @Test fun pausedBlockDoesNotCleanTarget() {
        assertEquals(targets, reduce(listOf(event(1, ActivityEventType.RESUMED)), BlockWindow(false, false)).packages)
    }
    @Test fun pictureInPicturePausedWindowIsNotForeground() {
        val events = listOf(event(1, ActivityEventType.RESUMED), event(2, ActivityEventType.PAUSED),
            event(3, ActivityEventType.RESUMED, other), event(4, ActivityEventType.RESUMED, cadence))
        // The paused PiP game is not foreground alongside a full-screen or split-screen activity.
        for (window in listOf(BlockWindow(true, false), BlockWindow(true, true), BlockWindow(false, false))) {
            assertEquals(setOf(other.packageName, cadence.packageName), reduce(events, window).packages)
        }
    }
    @Test fun pauseAfter24HourBoundaryDoesNotInventResume() {
        val now = 2 * 86_400_000L
        val boundary = usageQueryStart(now, bootTime = 0, previousRead = null)
        assertEquals(86_400_000L, boundary)
        val events = listOf(event(boundary - 1, ActivityEventType.RESUMED), event(boundary + 1, ActivityEventType.PAUSED))
        val first = reduce(events.filter { it.time >= boundary }, now = now)
        assertTrue(first.packages.isEmpty())
        val nextRead = now + 1_000
        val from = usageQueryStart(nextRead, bootTime = 0, previousRead = now)
        val next = reduceForeground(first.state, events.filter { it.time >= from }, nextRead, targets, BlockWindow())
        assertTrue(next.packages.isEmpty())
    }
    @Test fun longRunningResumedClassDoesNotExpireAt24Hours() {
        val first = reduce(listOf(event(1, ActivityEventType.RESUMED)))
        assertEquals(targets, reduceForeground(first.state, emptyList(), 86_400_100, targets, BlockWindow()).packages)
    }
    @Test fun overlappingQueryDoesNotResurrectCleanedState() {
        val resumed = event(1, ActivityEventType.RESUMED)
        val first = reduce(listOf(resumed), BlockWindow(true, false))
        assertTrue(reduceForeground(first.state, listOf(resumed), 101, targets, BlockWindow()).packages.isEmpty())
    }
    @Test fun newResumeAfterCleanupIsDetected() {
        val first = reduce(listOf(event(1, ActivityEventType.RESUMED)), BlockWindow(true, false))
        assertEquals(targets, reduceForeground(first.state, listOf(event(101, ActivityEventType.RESUMED)), 102, targets, BlockWindow()).packages)
    }
    @Test fun multipleClassesAreTrackedIndependently() {
        assertEquals(targets, reduce(listOf(event(1, ActivityEventType.RESUMED),
            event(2, ActivityEventType.RESUMED, game.copy(className = "Second")), event(3, ActivityEventType.PAUSED))).packages)
    }
    @Test fun dedupAndInactiveStateAreBounded() {
        val first = reduce(listOf(event(1, ActivityEventType.RESUMED), event(2, ActivityEventType.PAUSED)))
        val later = reduceForeground(first.state, emptyList(), 20_000, targets, BlockWindow())
        assertTrue(later.state.activities.isEmpty())
        assertTrue(later.state.seen.isEmpty())
    }
    @Test fun initialQueryUsesLaterOfBootAnd24Hours() {
        assertEquals(100L, usageQueryStart(500, 100, null))
        assertEquals(13_600_000L, usageQueryStart(100_000_000, 100, null))
    }
    @Test fun subsequentQueryOverlapsTenSecondsAndHandlesSmallClockRollback() {
        assertEquals(40_000L, usageQueryStart(51_000, 100, 50_000))
        assertEquals(39_000L, usageQueryStart(49_000, 100, 50_000))
    }
    @Test fun emptyHistoryIsUnknown() { assertTrue(reduce(emptyList()).packages.isEmpty()) }
    @Test fun launchFailsAtThreeSecondsWithoutResettingOnRetry() {
        val first = updateLaunchStatus(LaunchStatus(), 100, false, true)
        val retry = updateLaunchStatus(first, 2_100, false, true)
        assertEquals(first, retry)
        assertFalse(updateLaunchStatus(retry, 3_099, false, true).failed)
        assertTrue(updateLaunchStatus(retry, 3_100, false, true).failed)
    }
    @Test fun failureIsOnePersistentStateUntilBlockResumes() {
        val failed = LaunchStatus(100, true)
        assertEquals(failed, updateLaunchStatus(failed, 5_000, false, false))
        assertEquals(LaunchStatus(), updateLaunchStatus(failed, 5_001, true, false))
    }
    @Test fun cleanedResumeDoesNotReturnAfterScreenOffLongerThanOverlap() {
        val resumed = event(9_000, ActivityEventType.RESUMED)
        val lastRead = 10_000L
        val initial = reduce(listOf(resumed), now = lastRead)
        val cleaned = acknowledgeBlockWindow(initial.state, lastRead, targets, BlockWindow(true, false))
        // Home, screen off, and screen on callbacks occur without another successful query.
        val wakeTime = 40_000L
        val atWake = acknowledgeBlockWindow(cleaned, lastRead, targets, BlockWindow(false, false))
        val from = usageQueryStart(wakeTime, bootTime = 0, previousRead = lastRead)
        assertTrue(resumed.time >= from)
        val reread = reduceForeground(atWake, listOf(resumed).filter { it.time >= from }, wakeTime, targets, BlockWindow())
        assertTrue(reread.packages.isEmpty())
        val fresh = reduceForeground(reread.state, listOf(event(wakeTime + 1, ActivityEventType.RESUMED)), wakeTime + 2, targets, BlockWindow())
        assertEquals(targets, fresh.packages)
    }
    @Test fun inFlightQueryKeepsFullscreenAcknowledgementAfterQuickHome() {
        val result = completeForegroundQuery(ForegroundState(), listOf(event(1, ActivityEventType.RESUMED)),
            100, targets, BlockWindow(false, false), acknowledgedTargets = targets)
        assertTrue(result.packages.isEmpty())
    }
    @Test fun inFlightQueryDoesNotCleanSplitScreenTarget() {
        val result = completeForegroundQuery(ForegroundState(), listOf(event(1, ActivityEventType.RESUMED)),
            100, targets, BlockWindow(true, true), acknowledgedTargets = emptySet())
        assertEquals(targets, result.packages)
    }

}
