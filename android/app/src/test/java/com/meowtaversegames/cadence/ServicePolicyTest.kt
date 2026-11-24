package com.meowtaversegames.cadence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import java.time.Instant
import org.junit.Test

class ServicePolicyTest {
    @Test fun permittedEntrypointsStartDirectly() {
        for (source in listOf(ReconcileSource.ALARM, ReconcileSource.SYSTEM_BROADCAST, ReconcileSource.VISIBLE_ACTIVITY)) {
            assertEquals(ServiceAction.START_DIRECTLY, serviceAction(true, source, ServicePhase.STOPPED))
        }
    }
    @Test fun backgroundChangeUsesImmediateAlarm() {
        assertEquals(ServiceAction.IMMEDIATE_ALARM, serviceAction(true, ReconcileSource.DATA_CHANGED, ServicePhase.STOPPED))
    }
    @Test fun runningServiceIsNotStartedAgain() {
        for (source in ReconcileSource.entries) {
            assertEquals(ServiceAction.KEEP, serviceAction(true, source, ServicePhase.RUNNING))
        }
    }
    @Test fun freeTimeStopsService() {
        for (source in ReconcileSource.entries) {
            assertEquals(ServiceAction.STOP, serviceAction(false, source, ServicePhase.RUNNING))
        }
    }
    @Test fun pendingStartCannotBeStoppedBeforePromotion() {
        for (source in ReconcileSource.entries) {
            assertEquals(ServiceAction.KEEP, serviceAction(false, source, ServicePhase.START_REQUESTED))
        }
        // onStartCommand promotes first, then reconciles and stops safely.
        assertEquals(ServiceAction.STOP, serviceAction(false, ReconcileSource.SERVICE, ServicePhase.RUNNING))
    }
    @Test fun pendingStartDoesNotCreateAnotherRequest() {
        for (source in ReconcileSource.entries) {
            assertEquals(ServiceAction.KEEP, serviceAction(true, source, ServicePhase.START_REQUESTED))
        }
    }
    @Test fun denialOutsideAlarmRetriesThroughAlarm() {
        for (source in ReconcileSource.entries.filter { it != ReconcileSource.ALARM }) {
            assertEquals(ServiceAction.IMMEDIATE_ALARM, serviceAction(true, source, ServicePhase.STOPPED, startDenied = true))
        }
    }
    @Test fun denialInsideAlarmNeverReschedulesImmediateAlarm() {
        assertEquals(ServiceAction.KEEP, serviceAction(true, ReconcileSource.ALARM, ServicePhase.STOPPED, startDenied = true))
    }
    @Test fun stickyPromotionDenialRetriesWithoutRecursiveStart() {
        assertEquals(ServiceAction.IMMEDIATE_ALARM, serviceAction(true, ReconcileSource.SERVICE, ServicePhase.START_REQUESTED, startDenied = true))
        assertEquals(ServiceAction.KEEP, serviceAction(true, ReconcileSource.SERVICE, ServicePhase.STOPPED))
    }
    @Test fun returningToFocusDuringStopRequestsAnotherStart() {
        for (source in listOf(ReconcileSource.ALARM, ReconcileSource.SYSTEM_BROADCAST, ReconcileSource.VISIBLE_ACTIVITY, ReconcileSource.SERVICE)) {
            assertEquals(ServiceAction.START_DIRECTLY, serviceAction(true, source, ServicePhase.STOP_REQUESTED))
        }
    }
    @Test fun backgroundChangeDuringStopStillUsesAnAlarm() {
        assertEquals(ServiceAction.IMMEDIATE_ALARM, serviceAction(true, ReconcileSource.DATA_CHANGED, ServicePhase.STOP_REQUESTED))
    }
    @Test fun freeTimeDuringStopKeepsStopping() {
        for (source in ReconcileSource.entries) {
            assertEquals(ServiceAction.STOP, serviceAction(false, source, ServicePhase.STOP_REQUESTED))
        }
    }

    @Test fun notificationCannotBeRepublishedWhileStopIsPendingOrFinished() {
        val now = Instant.parse("2030-01-01T00:00:00Z")
        for (phase in listOf(ServicePhase.STOP_REQUESTED, ServicePhase.STOPPED, ServicePhase.START_REQUESTED)) {
            assertFalse(shouldShowBlockingNotification(false, phase, now.plusSeconds(60), now))
        }
    }
    @Test fun notificationStopsAtTheExactEndEvenBeforeServiceDestruction() {
        val end = Instant.parse("2030-01-01T00:00:00Z")
        assertTrue(shouldShowBlockingNotification(false, ServicePhase.RUNNING, end, end.minusSeconds(1)))
        assertFalse(shouldShowBlockingNotification(false, ServicePhase.RUNNING, end, end))
        assertFalse(shouldShowBlockingNotification(false, ServicePhase.RUNNING, end, end.plusSeconds(1)))
        assertFalse(shouldShowBlockingNotification(false, ServicePhase.RUNNING, null, end))
    }
    @Test fun startupFailureNotificationDoesNotRequireARunningService() {
        assertTrue(shouldShowBlockingNotification(true, ServicePhase.STOPPED, null, Instant.EPOCH))
    }
    @Test fun notificationPresenceChecksAreThrottledToFiveSeconds() {
        assertTrue(notificationCheckDue(null, 0))
        assertFalse(notificationCheckDue(1_000, 1_000))
        assertFalse(notificationCheckDue(1_000, 2_000))
        assertFalse(notificationCheckDue(1_000, 5_999))
        assertTrue(notificationCheckDue(1_000, 6_000))
    }

}
