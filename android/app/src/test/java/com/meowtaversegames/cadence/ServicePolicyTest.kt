package com.meowtaversegames.cadence

import org.junit.Assert.assertEquals
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
}
