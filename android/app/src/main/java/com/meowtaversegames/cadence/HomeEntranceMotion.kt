package com.meowtaversegames.cadence

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Rows retain this one object for the screen lifetime, including across HOME intents. */
@Stable
class EntranceMotionState {
    var enabled by mutableStateOf(true)
        private set
    var suppression by mutableStateOf(EntranceSuppression())
        private set
    private var homeRequest: Int? = null
    private val animations = mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>()
    private val jobs = mutableMapOf<String, Job>()
    private val tracker = EntranceTracker()
    val suppressed: Boolean get() = suppression.suppressed

    fun configure(enabled: Boolean, homeRequest: Int) {
        this.enabled = enabled
        if (!enabled) clear()
        if (this.homeRequest != homeRequest) {
            this.homeRequest = homeRequest
            suppress()
        }
    }

    fun suppress() {
        suppression = suppression.request()
        clear()
    }

    fun releaseAfterIdleFrame(generation: Long, scrolling: Boolean) {
        suppression = suppression.afterIdleFrame(generation, scrolling)
    }

    fun motion(key: String): RowMotion =
        if (!enabled || suppressed) RowMotion() else entranceMotion(animations[key]?.value ?: 1f)

    private fun remove(key: String) {
        // Remove ownership before cancelling; an old job must not remove a returning row.
        animations.remove(key)
        jobs.remove(key)?.cancel()
        tracker.completed(key)
    }

    fun clear() {
        animations.keys.toList().forEach(::remove)
    }

    fun observe(frame: EntranceFrame, scope: CoroutineScope) {
        val changes = tracker.observe(frame)
        if (changes.clearAll) clear() else changes.remove.forEach(::remove)
        changes.start.forEachIndexed { position, key ->
            val progress = Animatable(0f)
            animations[key] = progress
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(entranceDelayMillis(position).toLong())
                    progress.animateTo(1f, tween(320, easing = Easing { t ->
                        val remaining = 1f - t
                        1f - remaining * remaining * remaining
                    }))
                } finally {
                    if (animations[key] === progress) {
                        animations.remove(key)
                        jobs.remove(key)
                        tracker.completed(key)
                    }
                }
            }
            jobs[key] = job
            job.start()
        }
    }
}

private data class EntranceObservation(val info: LazyListLayoutInfo, val frame: EntranceFrame)

@Composable
fun rememberEntranceMotion(listState: LazyListState, items: List<HomeItem>, enabled: Boolean,
    homeRequest: Int): EntranceMotionState {
    val state = remember(listState) { EntranceMotionState() }
    val keys = rememberUpdatedState(items.map { it.key })
    val eligible = rememberUpdatedState(items.filter { it !is HomeItem.Widget && it.key != "header" }.map { it.key }.toSet())
    SideEffect { state.configure(enabled, homeRequest) }

    // One collector for the screen lifetime. HOME only advances the observed generation.
    LaunchedEffect(listState, state) {
        coroutineScope {
            var lastInfo: LazyListLayoutInfo? = null
            var measurement = 0L
            try {
                snapshotFlow {
                    val info = listState.layoutInfo
                    val rows = info.visibleItemsInfo.filter {
                        it.size > 0 && it.offset < info.viewportEndOffset &&
                            it.offset.toLong() + it.size > info.viewportStartOffset && it.key in eligible.value
                    }.map { EntranceRow(it.key as String, it.offset) }
                    EntranceObservation(info, EntranceFrame(rows, keys.value, listState.isScrollInProgress,
                        state.enabled, state.suppressed, state.suppression.generation))
                }.collect { observation ->
                    if (observation.info !== lastInfo) measurement++
                    lastInfo = observation.info
                    state.observe(observation.frame.copy(measurement = measurement), this)
                }
            } finally {
                state.clear()
            }
        }
    }
    // Never keyed by suppression or HOME. Even an already idle request gets a release frame.
    LaunchedEffect(listState, state) {
        snapshotFlow { state.suppression to listState.isScrollInProgress }.collectLatest { (gate, scrolling) ->
            if (gate.suppressed && !scrolling) {
                withFrameNanos { }
                state.releaseAfterIdleFrame(gate.generation, listState.isScrollInProgress)
            }
        }
    }
    return state
}
