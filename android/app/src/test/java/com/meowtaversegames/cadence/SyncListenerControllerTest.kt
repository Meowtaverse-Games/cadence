package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SyncListenerControllerTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private class Subscription(val account: String, val callbacks: SnapshotCallbacks, var removed: Boolean = false)
    private class Timer(val delay: Long, val callback: () -> Unit, var cancelled: Boolean = false)
    private class Harness {
        var account: String? = "synthetic-account-a"
        var writes = 0
        var content = syntheticValue()
        val subscriptions = mutableListOf<Subscription>()
        val timers = mutableListOf<Timer>()
        val session = SyncSession(null, { content }, { content = it; writes++ }, {}, {}, {})
        val controller = SyncListenerController(session, { account },
            listen = { uid, callbacks ->
                val subscription = Subscription(uid, callbacks)
                subscriptions.add(subscription)
                val remove: () -> Unit = { subscription.removed = true }
                remove
            }, schedule = { delay, callback ->
                val timer = Timer(delay, callback)
                timers.add(timer)
                val cancel: () -> Unit = { timer.cancelled = true }
                cancel
            }, publish = {})
        init { controller.resume() }
        fun fail(code: String = "PERMISSION_DENIED") = subscriptions.last().callbacks.error(code)
    }

    @Test fun delaysAreBoundedAndErrorPolicyMatchesJapaneseMessages() {
        assertEquals(listOf(30_000L, 60_000L, 300_000L, 300_000L), (0..3).map(::listenerRetryDelayMillis))
        assertEquals(300_000L, listenerRetryDelayMillis(Int.MAX_VALUE))
        assertEquals(ListenerFailure(true, "時間割を読む権限がありません"), listenerFailure("PERMISSION_DENIED"))
        assertEquals(ListenerFailure(true, "同期できません"), listenerFailure("UNAVAILABLE"))
        for (code in listOf("UNAUTHENTICATED", "ERROR_USER_DISABLED", "USER_NOT_FOUND", "USER_TOKEN_EXPIRED", "INVALID_USER_TOKEN")) {
            assertEquals(ListenerFailure(false, SIGN_IN_REQUIRED), listenerFailure(code))
        }
    }

    @Test fun errorRetryAndValidSnapshotRecoverWithoutResigningIn() {
        val h = Harness()
        h.fail()
        assertTrue(h.subscriptions.single().removed)
        assertEquals("時間割を読む権限がありません", h.session.status.problem)
        assertEquals(30_000L, h.timers.single().delay)
        h.timers.single().callback()
        assertEquals(2, h.subscriptions.size)
        assertEquals("時間割を読む権限がありません", h.session.status.problem)
        h.subscriptions.last().callbacks.snapshot(syntheticValue("Changed"), false, now)
        assertEquals(1, h.writes)
        assertEquals(now, h.session.status.lastSync)
        assertEquals("同期済みです", h.session.status.text)
        assertEquals(1, h.subscriptions.count { !it.removed })
    }

    @Test fun repeatedFailuresBackOffAndSnapshotResetsDelay() {
        val h = Harness()
        repeat(4) { h.fail(); h.timers.last().callback() }
        assertEquals(listOf(30_000L, 60_000L, 300_000L, 300_000L), h.timers.map { it.delay })
        h.subscriptions.last().callbacks.snapshot(syntheticValue(), true, now)
        h.fail()
        assertEquals(30_000L, h.timers.last().delay)
    }

    @Test fun foregroundReattachesEarlyAndDoesNotDuplicateTheListener() {
        val h = Harness()
        h.controller.resume()
        assertEquals(1, h.subscriptions.size)
        h.fail()
        val old = h.timers.single()
        h.controller.resume()
        assertTrue(old.cancelled)
        old.callback() // Even a previously queued runnable cannot attach again.
        h.controller.resume()
        assertEquals(2, h.subscriptions.size)
        assertEquals(1, h.subscriptions.count { !it.removed })
    }

    @Test fun signOutCancelsPendingRetryAndRejectsLateEvents() {
        val h = Harness()
        val old = h.subscriptions.single()
        h.fail()
        h.account = null
        h.controller.accountChanged(null)
        val state = h.session.status
        assertTrue(h.timers.single().cancelled)
        h.timers.single().callback()
        old.callbacks.snapshot(syntheticValue("Old"), false, now)
        old.callbacks.error("UNAVAILABLE")
        assertEquals(1, h.subscriptions.size)
        assertEquals(0, h.writes)
        assertEquals(state, h.session.status)
        assertEquals(SIGN_IN_REQUIRED, state.problem)
    }

    @Test fun accountSwitchDiscardsOldRetryAndResetsBackoff() {
        val h = Harness()
        h.fail()
        val old = h.timers.single()
        h.account = "synthetic-account-b"
        h.controller.accountChanged(h.account)
        assertTrue(old.cancelled)
        old.callback()
        assertEquals(2, h.subscriptions.size)
        assertEquals("synthetic-account-b", h.subscriptions.last().account)
        h.fail()
        assertEquals(30_000L, h.timers.last().delay)
    }

    @Test fun retryRechecksLiveAuthBeforeAttaching() {
        val h = Harness()
        h.fail()
        // Authentication may change before its queued AuthStateListener notification is delivered.
        h.account = null
        h.timers.single().callback()
        assertEquals(1, h.subscriptions.size)
        assertEquals(SIGN_IN_REQUIRED, h.session.status.problem)
    }

    @Test fun authenticationErrorsStopRetriesIncludingForegroundAttempts() {
        for (code in listOf("UNAUTHENTICATED", "ERROR_USER_DISABLED")) {
            val h = Harness()
            h.fail(code)
            h.controller.resume()
            h.controller.accountChanged(h.account)
            assertEquals(1, h.subscriptions.size)
            assertTrue(h.subscriptions.single().removed)
            assertTrue(h.timers.isEmpty())
            assertEquals(SIGN_IN_REQUIRED, h.session.status.problem)
            h.controller.accountChanged(null)
            h.controller.resume() // Explicit sign-out / sign-in permits another attempt.
            assertEquals(2, h.subscriptions.size)
        }
    }

    @Test fun closeCancelsRetriesAndRejectsLateCallbacks() {
        val h = Harness()
        h.fail()
        h.controller.close()
        assertTrue(h.timers.single().cancelled)
        h.timers.single().callback()
        h.controller.resume()
        h.subscriptions.single().callbacks.snapshot(syntheticValue("Old"), false, now)
        assertEquals(1, h.subscriptions.size)
        assertEquals(0, h.writes)
    }

    @Test fun oldListenerCannotAffectReplacementListenerOrItsRetry() {
        val h = Harness()
        val old = h.subscriptions.single()
        h.fail()
        h.timers.single().callback()
        old.callbacks.snapshot(syntheticValue("Old"), false, now)
        old.callbacks.error("UNAUTHENTICATED")
        assertEquals(0, h.writes)
        assertFalse(h.subscriptions.last().removed)
        h.subscriptions.last().callbacks.snapshot(syntheticValue(), false, now)
        assertNull(h.session.status.problem)
    }

    @Test fun closeRemovesAnActiveListener() {
        val h = Harness()
        h.controller.close()
        assertTrue(h.subscriptions.single().removed)
        assertTrue(h.timers.isEmpty())
    }
}
