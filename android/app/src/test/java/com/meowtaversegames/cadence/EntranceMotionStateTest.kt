package com.meowtaversegames.cadence

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class EntranceMotionStateTest {
    // Keep animation jobs queued: state ownership can be tested without an Android frame clock.
    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
    }
    private val keys = listOf("a", "b", "c", "d")
    private fun frame(state: EntranceMotionState, vararg visible: String, scrolling: Boolean = true) =
        EntranceFrame(visible.mapIndexed { index, key -> EntranceRow(key, index * 80) }, keys,
            scrolling, state.enabled, state.suppressed, state.suppression.generation)

    private fun withState(block: (EntranceMotionState, CoroutineScope, QueuedDispatcher) -> Unit) {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(Job() + dispatcher)
        val state = EntranceMotionState()
        try {
            state.configure(true, 0)
            state.releaseAfterIdleFrame(state.suppression.generation, false)
            state.observe(frame(state, "a", scrolling = false), scope)
            block(state, scope, dispatcher)
        } finally {
            state.clear()
            scope.cancel()
        }
    }

    @Test fun configureKeepsGenerationUnlessHomeChangesAndSuppressesRepeatedHome() = withState { state, scope, _ ->
        state.observe(frame(state, "b"), scope)
        val generation = state.suppression.generation
        state.configure(true, 0)
        assertEquals(generation, state.suppression.generation)
        assertEquals(entranceMotion(0f), state.motion("b"))
        state.configure(true, 1)
        assertEquals(generation + 1, state.suppression.generation)
        assertTrue(state.suppressed)
        assertEquals(RowMotion(), state.motion("b"))
        state.configure(true, 2)
        assertEquals(generation + 2, state.suppression.generation)
    }

    @Test fun disablingClearsAnimationsAndEnablingDoesNotRestoreThem() = withState { state, scope, _ ->
        state.observe(frame(state, "b"), scope)
        state.configure(false, 0)
        assertFalse(state.enabled)
        assertEquals(RowMotion(), state.motion("b"))
        state.configure(true, 0)
        assertEquals(RowMotion(), state.motion("b"))
        state.observe(frame(state, "c"), scope)
        assertEquals(entranceMotion(0f), state.motion("c"))
    }

    @Test fun suppressClearsAnimationsEvenWhenAlreadySuppressed() = withState { state, scope, _ ->
        state.observe(frame(state, "b"), scope)
        state.suppress()
        val generation = state.suppression.generation
        state.suppress()
        assertEquals(generation + 1, state.suppression.generation)
        assertEquals(RowMotion(), state.motion("b"))
        state.releaseAfterIdleFrame(state.suppression.generation, false)
        assertEquals(RowMotion(), state.motion("b"))
    }

    @Test fun clearRemovesAnimationOwnershipButKeepsVisibilityBaseline() = withState { state, scope, _ ->
        state.observe(frame(state, "b"), scope)
        state.clear()
        assertEquals(RowMotion(), state.motion("b"))
        state.observe(frame(state, "b", "c"), scope)
        assertEquals(RowMotion(), state.motion("b"))
        assertEquals(entranceMotion(0f), state.motion("c"))
        assertEquals(RowMotion(), state.motion("unknown"))
    }

    @Test fun observeRemovesDepartedRowsAndReturningRowsStartAgain() = withState { state, scope, _ ->
        state.observe(frame(state, "b"), scope)
        assertEquals(entranceMotion(0f), state.motion("b"))
        state.observe(frame(state, "c"), scope)
        assertEquals(RowMotion(), state.motion("b"))
        state.observe(frame(state, "b"), scope)
        assertEquals(entranceMotion(0f), state.motion("b"))
    }

    @Test fun cancelledOldJobsCannotRemoveReturningRows() = withState { state, scope, dispatcher ->
        state.observe(frame(state, "b"), scope)
        val oldJob = dispatcher.tasks.removeFirst()
        state.suppress()
        state.observe(frame(state, "a", scrolling = false), scope)
        state.releaseAfterIdleFrame(state.suppression.generation, false)
        state.observe(frame(state, "a", scrolling = false), scope)
        state.observe(frame(state, "b"), scope)
        oldJob.run()
        assertEquals(entranceMotion(0f), state.motion("b"))
    }

    @Test fun idleReleaseRetriesScrollRacesAndStopsAfterSuccess() = withState { state, _, _ ->
        state.suppress()
        val generation = state.suppression.generation
        repeat(3) {
            assertEquals(IdleRelease.RETRY, state.releaseAfterIdleFrame(generation, true))
            assertTrue(state.suppressed)
        }
        assertEquals(IdleRelease.RELEASE, state.releaseAfterIdleFrame(generation, false))
        assertFalse(state.suppressed)
        assertEquals(IdleRelease.STOP, state.releaseAfterIdleFrame(generation, false))
    }

    @Test fun newGenerationStopsOldReleaseRetries() = withState { state, _, _ ->
        state.suppress()
        val generation = state.suppression.generation
        assertEquals(IdleRelease.RETRY, state.releaseAfterIdleFrame(generation, true))
        state.suppress()
        assertEquals(IdleRelease.STOP, state.releaseAfterIdleFrame(generation, false))
        assertTrue(state.suppressed)
        assertEquals(IdleRelease.RELEASE, state.releaseAfterIdleFrame(state.suppression.generation, false))
    }
}
