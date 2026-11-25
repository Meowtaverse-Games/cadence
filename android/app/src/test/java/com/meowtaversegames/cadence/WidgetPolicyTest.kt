package com.meowtaversegames.cadence

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WidgetPolicyTest {
    private val a = SavedWidget(11, "sample.clock/Widget", 72f)
    private val b = SavedWidget(12, "sample.weather/Widget", 144f)
    private val c = SavedWidget(13, "sample.calendar/Widget", 216f)

    @Test fun missingFileAndDefaultHeight() {
        val store = WidgetStore({ null }, { fail("read must not write") })
        assertEquals(ReadValue(WidgetDocument(), false), store.read())
        assertEquals(a, WidgetJson.decode("""{"widgets":[{"id":11,"provider":"sample.clock/Widget"}]}""").widgets.single())
    }
    @Test fun orderHeightAndUnknownFieldsRoundTrip() {
        var file = """{"future":{"flag":true},"widgets":[{"id":11,"provider":"sample.clock/Widget","height":72,"futureItem":[1,2]}]}"""
        val store = WidgetStore({ file }, { file = it })
        val first = store.read().value!!.widgets.single()
        store.save(listOf(b, first.copy(height = 216f)))
        assertEquals(listOf(12, 11), store.read().value!!.widgets.map { it.id })
        assertEquals(216f, store.read().value!!.widgets.last().height)
        val json = Json.parseToJsonElement(file).jsonObject
        assertEquals(Json.parseToJsonElement("""{"flag":true}"""), json["future"])
        assertEquals(Json.parseToJsonElement("[1,2]"), json["widgets"]!!.jsonArray.last().jsonObject["futureItem"])
    }
    @Test fun corruptedFilesNeverBecomeWritableEmptyFiles() {
        for (text in listOf("", "{", "null", "[]", """{"widgets":null}""",
            """{"widgets":[{"id":0,"provider":"sample.clock/Widget"}]}""",
            """{"widgets":[{"id":1,"provider":"invalid"}]}""",
            """{"widgets":[{"id":1,"provider":"sample.clock/Widget","height":-1}]}""")) {
            var writes = 0; var logs = 0
            val store = WidgetStore({ text }, { writes++ }, { logs++ })
            assertTrue(store.read().failed)
            assertThrows(Exception::class.java) { store.save(listOf(a)) }
            assertEquals(0, writes)
            assertEquals(2, logs)
        }
    }
    @Test fun duplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WidgetJson.encode(WidgetDocument(listOf(a, a))) }
    }
    @Test fun ioErrorAndCorruptionAfterReadingAreProtected() {
        var broken = false
        val store = WidgetStore({ if (broken) throw IOException() else "{}" }, { fail("must not write") })
        assertFalse(store.read().failed)
        broken = true
        assertTrue(store.read().failed)
        assertThrows(Exception::class.java) { store.save(listOf(a)) }
    }
    @Test fun partialWriteRollsBackAndLeavesOriginalDocument() {
        val original = WidgetJson.encode(WidgetDocument(listOf(a)))
        var committed = original
        var rolledBack = false
        var finished = false
        val store = WidgetStore({ committed }, { text ->
            atomicTextWrite(text, {
                object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, off: Int, len: Int) {
                        super.write(bytes, off, minOf(10, len))
                        throw IOException("synthetic partial write")
                    }
                }
            }, { stream -> committed = stream.toString("UTF-8"); finished = true }, { stream ->
                stream.close(); rolledBack = true
            })
        })
        assertThrows(IOException::class.java) { store.save(listOf(b)) }
        assertEquals(original, committed)
        assertEquals(listOf(a), store.read().value!!.widgets)
        assertTrue(rolledBack)
        assertFalse(finished)
    }
    @Test fun atomicSuccessCommitsUtf8AndFinishFailureRollsBack() {
        var text = ""
        atomicTextWrite("合成", { ByteArrayOutputStream() }, { text = it.toString("UTF-8") }, { fail("unexpected rollback") })
        assertEquals("合成", text)
        var rollback = false
        assertThrows(IOException::class.java) {
            atomicTextWrite("new", { ByteArrayOutputStream() }, { throw IOException() }, { rollback = true })
        }
        assertTrue(rollback)
    }
    @Test fun bindGrantedOrPermissionResultUsesSameTransition() {
        for (configuration in listOf(false, true)) {
            val start = WidgetPending(a, WidgetStage.BIND, configuration)
            val bound = transitionWidget(start, WidgetEvent.BOUND)
            assertEquals(if (configuration) WidgetStage.CONFIGURE else WidgetStage.SAVE, bound.pending!!.stage)
            assertNull(bound.deleteId)
            assertEquals(bound, transitionWidget(bound.pending, WidgetEvent.BOUND))
        }
    }
    @Test fun configurationIsRequiredUnlessOptionalOn31OrLater() {
        for (api in listOf(29, 30, 31, 37)) for (hasConfigure in listOf(false, true)) for (optional in listOf(false, true)) {
            assertEquals(hasConfigure && !(api >= 31 && optional), needsWidgetConfiguration(hasConfigure, api, optional))
        }
    }
    @Test fun configureSaveAndDuplicateResults() {
        val pending = WidgetPending(a, WidgetStage.CONFIGURE, true)
        val saving = transitionWidget(pending, WidgetEvent.CONFIGURED)
        assertEquals(WidgetStage.SAVE, saving.pending!!.stage)
        assertEquals(saving, transitionWidget(saving.pending, WidgetEvent.CONFIGURED))
        val done = transitionWidget(saving.pending, WidgetEvent.SAVED)
        assertNull(done.pending); assertNull(done.deleteId)
        for (event in WidgetEvent.entries) assertEquals(WidgetTransition(null), transitionWidget(null, event))
    }
    @Test fun cancellationAndFailureDeleteEveryUncommittedId() {
        for (stage in listOf(WidgetStage.BIND, WidgetStage.CONFIGURE, WidgetStage.SAVE)) {
            for (event in listOf(WidgetEvent.CANCELLED, WidgetEvent.FAILED)) {
                assertEquals(WidgetTransition(null, a.id), transitionWidget(WidgetPending(a, stage, true), event))
            }
        }
    }
    @Test fun reconfigurationNeverDeletesExistingId() {
        for (event in listOf(WidgetEvent.CANCELLED, WidgetEvent.FAILED, WidgetEvent.CONFIGURED)) {
            assertEquals(WidgetTransition(null), transitionWidget(WidgetPending(a, WidgetStage.RECONFIGURE, true), event))
        }
    }
    @Test fun wrongPhaseResultsAreIgnored() {
        val bind = WidgetPending(a, WidgetStage.BIND, false)
        assertEquals(WidgetTransition(bind), transitionWidget(bind, WidgetEvent.CONFIGURED))
        assertEquals(WidgetTransition(bind), transitionWidget(bind, WidgetEvent.SAVED))
    }
    @Test fun cleanupProtectsSavedPendingAndUnreadableFiles() {
        assertEquals(listOf(12, 13), orphanWidgetIds(listOf(11, 12, 13), listOf(a), null, false))
        assertEquals(listOf(13), orphanWidgetIds(listOf(11, 12, 13), listOf(a), 12, false))
        assertEquals(emptyList<Int>(), orphanWidgetIds(listOf(11, 12, 13), emptyList(), null, true))
    }
    @Test fun unavailableProvidersAndBlockingDoNotMutateSavedOrder() {
        val saved = listOf(a, b, c)
        assertEquals(listOf(a, c), visibleWidgets(saved, setOf(11, 13), emptySet(), false))
        assertEquals(listOf(b, c), visibleWidgets(saved, setOf(11, 12, 13), setOf(a.packageName), true))
        assertEquals(saved, visibleWidgets(saved, setOf(11, 12, 13), setOf(a.packageName), false))
        assertEquals(3, saved.size)
    }
    @Test fun onlyDefinitivePackageRemovalDiscardsWidgets() {
        assertEquals(emptyList<SavedWidget>(), removedPackageWidgets(listOf(a, b), a.packageName, true))
        assertEquals(listOf(a), removedPackageWidgets(listOf(a, b), a.packageName, false))
        assertEquals(emptyList<SavedWidget>(), removedPackageWidgets(listOf(a, b), "sample.other", false))
    }
    @Test fun limitCountsAvailableProvidersIncludingBlockedOnesAndReadFailureWins() {
        assertNull(widgetAddReason(listOf(a), false, setOf(a.id)))
        assertNotNull(widgetAddReason((1..5).map { a.copy(id = it) }, false, (1..5).toSet()))
        assertNotNull(widgetAddReason(emptyList(), true, emptySet()))
    }
    @Test fun cleanupDropsSystemDeletedIdsButProtectsPendingAndFailedInventory() {
        val saved = listOf(a, b, c)
        assertEquals(listOf(a), retainedHostWidgets(saved, setOf(a.id), null))
        assertEquals(listOf(a, b), retainedHostWidgets(saved, setOf(a.id), b.id))
        assertEquals(saved, retainedHostWidgets(saved, null, null))
        assertEquals(emptyList<SavedWidget>(), retainedHostWidgets(saved, emptySet(), null))
    }
    @Test fun unavailableProvidersRemainSavedWithoutTakingSlots() {
        val saved = (1..6).map { a.copy(id = it) }
        val retained = retainedHostWidgets(saved, (1..6).toSet(), null)
        assertEquals(saved, retained)
        assertNull(widgetAddReason(retained, false, setOf(1, 2, 3, 4)))
        assertNotNull(widgetAddReason(retained, false, setOf(1, 2, 3, 4, 5)))
        // Blocking affects visibility, not the available-provider set used for the limit.
        assertTrue(visibleWidgets(retained, (1..6).toSet(), setOf(a.packageName), true).isEmpty())
        assertNotNull(widgetAddReason(retained, false, (1..6).toSet()))
    }
    @Test fun sizeNotificationsSurviveViewRecreationAndTrackEachId() {
        val reports = WidgetSizeReports()
        var sends = 0
        reports.report(11, 320f, 144f) { sends++; true }
        reports.report(11, 320f, 144f) { fail("recreated view must not resend"); true }
        reports.report(12, 320f, 144f) { sends++; true }
        reports.report(11, 640f, 144f) { sends++; true }
        reports.report(11, 640f, 216f) { sends++; true }
        assertEquals(4, sends)
        reports.forget(11)
        reports.report(11, 640f, 216f) { sends++; true }
        assertEquals(5, sends)
    }
    @Test fun failedSizeNotificationsAreRetriedAndUnmeasuredWidthsAreIgnored() {
        val reports = WidgetSizeReports()
        var sends = 0
        reports.report(11, 1f, 144f) { fail("not measured yet"); true }
        reports.report(11, 320f, 144f) { sends++; false }
        reports.report(11, 320f, 144f) { sends++; true }
        reports.report(11, 320f, 144f) { fail("already sent"); true }
        assertEquals(2, sends)
    }
    @Test fun initialHeightUsesAtLeastOneCellAndModernTargetCells() {
        assertEquals(72f, initialWidgetHeight(0f, 0, 29))
        assertEquals(72f, initialWidgetHeight(1f, 0, 31))
        assertEquals(216f, initialWidgetHeight(1f, 3, 31))
        assertEquals(300f, initialWidgetHeight(300f, 3, 31))
        assertEquals(72f, initialWidgetHeight(1f, 3, 30))
        assertEquals(72f, initialWidgetHeight(1f, -1, 31))
    }
    @Test fun resizeHeightNeverFallsBelowOneCell() {
        assertEquals(72f..800f, WidgetHeight(1f, 0f, 0f, true).bounds(1000f))
        assertEquals(72f..800f, WidgetHeight(1f, 1f, 0f, true).bounds(1000f))
        assertEquals(72f, WidgetHeight(1f, 1f, 0f, true).step(72f, -1, 1000f))
        assertEquals(72f..72f, WidgetHeight(1f, 1f, 20f, true).bounds(50f))
    }
    @Test fun movesSkipHiddenItemsWithoutChangingTheirSlots() {
        assertEquals(listOf(c, b, a), moveWidget(listOf(a, b, c), listOf(11, 13), 11, 1))
        assertEquals(listOf(c, b, a), moveWidget(listOf(a, b, c), listOf(11, 13), 13, -1))
        assertFalse(canMoveWidget(listOf(11, 13), 11, -1))
        assertFalse(canMoveWidget(listOf(11, 13), 13, 1))
        assertFalse(canMoveWidget(listOf(11, 13), 12, 1))
        assertEquals(listOf(a, b), moveWidget(listOf(a, b), listOf(11, 12), 11, 0))
    }
    @Test fun heightStepsRespectMinMaxAndScreenCap() {
        val policy = WidgetHeight(144f, 72f, 300f, true)
        assertEquals(144f, policy.step(72f, 1, 1000f))
        assertEquals(72f, policy.step(100f, -1, 1000f))
        assertEquals(300f, policy.step(280f, 1, 1000f))
        assertEquals(160f, policy.step(144f, 1, 200f))
    }
    @Test fun zeroResizeDimensionsUseMinimumAndScreenCap() {
        val policy = WidgetHeight(144f, 0f, 0f, true)
        assertEquals(144f..800f, policy.bounds(1000f))
        assertEquals(800f, policy.step(780f, 1, 1000f))
        assertEquals(80f..80f, policy.bounds(100f))
    }
    @Test fun nonVerticalWidgetsDoNotResize() {
        assertEquals(144f, WidgetHeight(144f, 72f, 0f, false).step(144f, 1, 1000f))
        assertEquals(144f, WidgetHeight(144f, 72f, 0f, false).step(144f, -1, 100f))
    }
}
