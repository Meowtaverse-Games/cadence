package com.meowtaversegames.cadence

import com.meowtaversegames.cadence.core.TimetableValue
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SyncSessionTest {
    private val before = Instant.parse("2026-01-01T00:00:00Z")
    private val now = before.plusSeconds(60)
    private class Harness(initial: TimetableValue? = syntheticValue(), last: Instant? = null) {
        var content = initial
        var persistedTime = last
        var writes = 0
        var changes = 0
        var timeWrites = 0
        var failSave = false
        var failTime = false
        val logs = mutableListOf<String>()
        val session = SyncSession(last, { content }, {
            if (failSave) error("synthetic failure")
            content = it; writes++
        }, { changes++ }, {
            if (failTime) error("synthetic failure")
            persistedTime = it; timeWrites++
        }, logs::add)
        val token = session.begin(true)
    }

    @Test fun changedServerSnapshotSavesAndReconcilesOnce() {
        val h = Harness()
        h.session.snapshot(h.token, syntheticValue("Changed"), false, now)
        assertEquals(1, h.writes); assertEquals(1, h.changes)
        assertEquals(now, h.persistedTime); assertEquals(now, h.session.status.lastSync)
        assertNull(h.session.status.problem)
        assertEquals(listOf("sync_snapshot fromCache=false valid changed"), h.logs)
    }

    @Test fun sameServerSnapshotOnlyUpdatesLastSync() {
        val h = Harness(last = before)
        h.session.snapshot(h.token, syntheticValue(), false, now)
        assertEquals(0, h.writes); assertEquals(0, h.changes)
        assertEquals(now, h.persistedTime)
        assertEquals("同期済みです", h.session.status.text)
        assertEquals(listOf("sync_snapshot fromCache=false valid same"), h.logs)
    }

    @Test fun firstValidSnapshotCreatesCache() {
        val h = Harness(initial = null)
        h.session.snapshot(h.token, syntheticValue(), false, now)
        assertEquals(1, h.writes); assertEquals(1, h.changes)
    }

    @Test fun changedCachedSnapshotAppliesWithoutAdvancingLastSync() {
        val h = Harness(last = before)
        h.session.snapshot(h.token, syntheticValue("Changed"), true, now)
        assertEquals(1, h.writes); assertEquals(1, h.changes)
        assertEquals(before, h.session.status.lastSync); assertEquals(0, h.timeWrites)
        assertEquals("キャッシュの時間割を使用しています", h.session.status.text)
        assertEquals(listOf("sync_snapshot fromCache=true valid changed"), h.logs)
    }

    @Test fun cachedThenServerMetadataOnlySnapshotConfirmsLastSync() {
        val h = Harness(initial = null)
        h.session.snapshot(h.token, syntheticValue(), true, before)
        assertNull(h.session.status.lastSync)
        h.session.snapshot(h.token, syntheticValue(), false, now)
        assertEquals(1, h.writes); assertEquals(1, h.changes); assertEquals(1, h.timeWrites)
        assertEquals(now, h.session.status.lastSync)
        assertEquals(listOf("sync_snapshot fromCache=true valid changed", "sync_snapshot fromCache=false valid same"), h.logs)
    }

    @Test fun sameCachedSnapshotDoesNotWriteAnything() {
        val h = Harness(last = before)
        h.session.snapshot(h.token, syntheticValue(), true, now)
        assertEquals(0, h.writes); assertEquals(0, h.changes); assertEquals(0, h.timeWrites)
        assertEquals(before, h.session.status.lastSync)
        assertEquals(listOf("sync_snapshot fromCache=true valid same"), h.logs)
    }

    @Test fun missingSnapshotsPreserveCacheAndTime() {
        for (fromCache in listOf(true, false)) {
            val h = Harness(last = before)
            val original = h.content
            h.session.snapshot(h.token, null, fromCache, now)
            assertSame(original, h.content); assertEquals(0, h.changes); assertEquals(0, h.timeWrites)
            assertEquals(before, h.session.status.lastSync)
            assertEquals("サーバーに時間割がありません", h.session.status.problem)
            assertEquals(listOf("sync_snapshot fromCache=$fromCache missing"), h.logs)
        }
    }

    @Test fun invalidSnapshotsPreserveCacheAndTimeWithValidationCode() {
        for (fromCache in listOf(true, false)) {
            val h = Harness(last = before)
            val original = h.content
            h.session.snapshot(h.token, TimetableValue.ObjectValue(emptyMap()), fromCache, now)
            assertSame(original, h.content); assertEquals(0, h.writes); assertEquals(0, h.changes)
            assertEquals(before, h.session.status.lastSync)
            assertEquals("時間割を読み込めません: schema_version", h.session.status.problem)
            assertEquals(listOf("sync_snapshot fromCache=$fromCache invalid schema_version"), h.logs)
        }
    }

    @Test fun listenerErrorsPreserveCacheAndTime() {
        val expected = mapOf("PERMISSION_DENIED" to "時間割を読む権限がありません",
            "UNAUTHENTICATED" to SIGN_IN_REQUIRED, "UNAVAILABLE" to "同期できません")
        for ((code, message) in expected) {
            val h = Harness(last = before)
            val original = h.content
            h.session.error(h.token, code)
            assertSame(original, h.content); assertEquals(0, h.changes); assertEquals(0, h.timeWrites)
            assertEquals(before, h.session.status.lastSync); assertEquals(message, h.session.status.problem)
            assertEquals(listOf("sync_listener_error $code"), h.logs)
        }
    }

    @Test fun signOutRejectsAllOldCallbacksAndKeepsCache() {
        val h = Harness(last = before)
        val original = h.content
        h.session.begin(false)
        val state = h.session.status
        h.session.snapshot(h.token, syntheticValue("Changed"), false, now)
        h.session.snapshot(h.token, null, false, now)
        h.session.snapshot(h.token, TimetableValue.NullValue, true, now)
        h.session.error(h.token, "PERMISSION_DENIED")
        assertEquals(state, h.session.status); assertSame(original, h.content)
        assertEquals(0, h.writes); assertEquals(0, h.changes); assertEquals(0, h.timeWrites)
        assertTrue(h.logs.isEmpty()); assertEquals(SIGN_IN_REQUIRED, state.problem)
    }

    @Test fun newSessionRejectsPreviousAccountCallbacks() {
        val h = Harness()
        h.session.begin(false)
        val next = h.session.begin(true)
        h.session.snapshot(h.token, syntheticValue("Old"), false, now)
        h.session.error(h.token, "UNAUTHENTICATED")
        assertEquals(0, h.writes); assertTrue(h.logs.isEmpty())
        h.session.snapshot(next, syntheticValue("New"), false, now)
        assertEquals(1, h.writes); assertNull(h.session.status.problem)
    }

    @Test fun cacheWriteFailureDoesNotReconcileOrAdvanceTime() {
        val h = Harness(last = before)
        val original = h.content
        h.failSave = true
        h.session.snapshot(h.token, syntheticValue("Changed"), false, now)
        assertSame(original, h.content); assertEquals(0, h.changes); assertEquals(0, h.timeWrites)
        assertEquals(before, h.session.status.lastSync)
        assertEquals("ローカルファイルを保存・読み込みできません", h.session.status.problem)
        h.failSave = false
        h.session.snapshot(h.token, syntheticValue("Changed"), false, now)
        assertEquals(1, h.changes); assertEquals(now, h.session.status.lastSync)
    }

    @Test fun lastSyncWriteFailureIsVisibleAndDoesNotClaimSuccess() {
        val h = Harness(last = before)
        h.failTime = true
        h.session.snapshot(h.token, syntheticValue(), false, now)
        assertEquals(before, h.session.status.lastSync)
        assertEquals("ローカルファイルを保存・読み込みできません", h.session.status.problem)
    }

    @Test fun subsequentValidSnapshotClearsError() {
        val h = Harness()
        h.session.error(h.token, "UNAVAILABLE")
        h.session.snapshot(h.token, syntheticValue(), false, now)
        assertNull(h.session.status.problem)
    }

    @Test fun comparisonReadsCurrentCacheIncludingDebugChanges() {
        val h = Harness()
        h.session.snapshot(h.token, syntheticValue(), false, before)
        h.content = syntheticValue("Debug change")
        h.session.snapshot(h.token, syntheticValue(), false, now)
        assertEquals(1, h.writes); assertEquals(1, h.changes)
    }

    @Test fun loggingDoesNotIncludeDocumentContents() {
        val h = Harness()
        h.session.snapshot(h.token, syntheticValue("Private synthetic marker"), false, now)
        assertFalse(h.logs.joinToString().contains("Private synthetic marker"))
        assertFalse(h.logs.joinToString().contains("Asia/Tokyo"))
    }
}
