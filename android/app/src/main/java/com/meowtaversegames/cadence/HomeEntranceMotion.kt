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
import kotlinx.coroutines.flow.first
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
    internal var readFrame: (() -> EntranceFrame)? = null

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

    fun releaseAfterIdleFrame(generation: Long, scrolling: Boolean): IdleRelease {
        val result = suppression.idleRelease(generation, scrolling)
        suppression = suppression.afterIdleFrame(generation, scrolling)
        return result
    }

    fun motion(key: String): RowMotion {
        if (!enabled || suppressed) return RowMotion()
        // Read layout snapshots only in the layer, never from row composition. Prediction
        // shares the collector's transition without consuming visibility or starting jobs.
        val changes = readFrame?.invoke()?.let(tracker::preview)
        if (changes?.clearAll == true) return RowMotion()
        val initialProgress = if (key in changes?.start.orEmpty()) 0f else 1f
        return entranceMotion(animations[key]?.value ?: initialProgress)
    }

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

/** Both drawing and collection assign the same measurement to the same layout snapshot. */
private class EntranceLayoutReader(
    private val listState: LazyListState,
    private val state: EntranceMotionState,
    private val keys: State<List<String>>,
    private val eligible: State<Set<String>>,
) {
    private var lastInfo: LazyListLayoutInfo? = null
    private var measurement = 0L

    fun read(): EntranceObservation {
        val info = listState.layoutInfo
        val rows = info.visibleItemsInfo.filter {
            it.size > 0 && it.offset < info.viewportEndOffset &&
                it.offset.toLong() + it.size > info.viewportStartOffset && it.key in eligible.value
        }.map { EntranceRow(it.key as String, it.offset) }
        return EntranceObservation(info, EntranceFrame(rows, keys.value, listState.isScrollInProgress,
            state.enabled, state.suppressed, state.suppression.generation,
            if (info !== lastInfo) measurement + 1 else measurement))
    }

    fun accept(observation: EntranceObservation) {
        lastInfo = observation.info
        measurement = observation.frame.measurement
    }
}

@Composable
fun rememberEntranceMotion(listState: LazyListState, items: List<HomeItem>, enabled: Boolean,
    homeRequest: Int): EntranceMotionState {
    val state = remember(listState) { EntranceMotionState() }
    val keys = rememberUpdatedState(items.map { it.key })
    val eligible = rememberUpdatedState(items.filter { it !is HomeItem.Widget && it.key != "header" }.map { it.key }.toSet())
    val reader = remember(listState, state) { EntranceLayoutReader(listState, state, keys, eligible) }
    SideEffect {
        state.readFrame = { reader.read().frame }
        state.configure(enabled, homeRequest)
    }

    // One collector for the screen lifetime. HOME only advances the observed generation.
    LaunchedEffect(listState, state) {
        coroutineScope {
            try {
                snapshotFlow { reader.read() }.collect { observation ->
                    reader.accept(observation)
                    state.observe(observation.frame, this)
                }
            } finally {
                state.clear()
            }
        }
    }
    // Never keyed by suppression or HOME. Even an already idle request gets a release frame.
    LaunchedEffect(listState, state) {
        snapshotFlow { state.suppression }.collectLatest { gate ->
            while (state.suppression.idleRelease(gate.generation, true) == IdleRelease.RETRY) {
                snapshotFlow { listState.isScrollInProgress }.first { !it }
                withFrameNanos { }
                if (state.releaseAfterIdleFrame(gate.generation, listState.isScrollInProgress) != IdleRelease.RETRY) break
            }
        }
    }
    return state
}
