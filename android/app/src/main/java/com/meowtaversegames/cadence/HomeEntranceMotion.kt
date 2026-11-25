package com.meowtaversegames.cadence

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private data class EntranceObservation(
    val info: LazyListLayoutInfo,
    val items: List<HomeItem>,
    val rows: List<EntranceRow>,
    val matchesLayout: Boolean,
    val scrolling: Boolean,
    val enabled: Boolean,
    val suppressed: Boolean,
)

/** Screen-owned animations: composition/prefetch of an individual row never starts one. */
@Composable
fun rememberEntranceProgress(listState: LazyListState, items: List<HomeItem>, enabled: Boolean,
    suppressed: Boolean, homeRequest: Int): Map<String, Animatable<Float, AnimationVector1D>> {
    val animations = remember(homeRequest) { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    val latestItems = rememberUpdatedState(items)
    val eligible = rememberUpdatedState(items.filter { it !is HomeItem.Widget && it.key != "header" }.map { it.key }.toSet())
    val latestEnabled = rememberUpdatedState(enabled)
    val latestSuppressed = rememberUpdatedState(suppressed)
    LaunchedEffect(listState, homeRequest) {
        coroutineScope {
            val jobs = mutableMapOf<String, Job>()
            var previous: Set<String>? = null
            var previousItems: List<HomeItem>? = null
            var previousInfo: LazyListLayoutInfo? = null
            var pendingLayoutInfo: LazyListLayoutInfo? = null
            fun clear() {
                jobs.values.toList().forEach { it.cancel() }
                jobs.clear()
                animations.clear()
            }
            try {
                snapshotFlow {
                    val info = listState.layoutInfo
                    val currentItems = latestItems.value
                    val rows = info.visibleItemsInfo.filter {
                        it.size > 0 && it.offset < info.viewportEndOffset &&
                            it.offset.toLong() + it.size > info.viewportStartOffset && it.key in eligible.value
                    }.map { EntranceRow(it.key as String, it.offset) }
                    EntranceObservation(info, currentItems, rows,
                        info.visibleItemsInfo.all { currentItems.getOrNull(it.index)?.key == it.key },
                        listState.isScrollInProgress, latestEnabled.value, latestSuppressed.value)
                }.collect { frame ->
                    val layoutChanged = previousItems != null && previousItems != frame.items
                    // Composition can publish new items before LazyColumn measures them. Baseline
                    // the following measurement too, so a rebuild during a fling cannot animate it.
                    if (layoutChanged) pendingLayoutInfo = frame.info.takeIf { it === previousInfo }
                    val resettingLayout = layoutChanged || pendingLayoutInfo != null || !frame.matchesLayout
                    if (pendingLayoutInfo != null && frame.info !== pendingLayoutInfo) pendingLayoutInfo = null
                    val current = frame.rows.map { it.key }.toSet()
                    val entering = enteringKeys(previous, frame.rows, frame.scrolling, frame.enabled,
                        frame.suppressed, resettingLayout)
                    previous = current.takeIf { frame.matchesLayout }
                    previousItems = frame.items
                    previousInfo = frame.info
                    if (!frame.enabled || frame.suppressed || resettingLayout) {
                        clear()
                    } else {
                        // Leaving the viewport forgets the key immediately, including delayed jobs.
                        animations.keys.filterNot { it in current }.forEach { key ->
                            jobs.remove(key)?.cancel()
                            animations.remove(key)
                        }
                        entering.forEachIndexed { position, key ->
                            val progress = Animatable(0f)
                            animations[key] = progress
                            val job = launch(start = CoroutineStart.LAZY) {
                                try {
                                    delay(entranceDelayMillis(position).toLong())
                                    progress.animateTo(1f, tween(320, easing = Easing { t ->
                                        val remaining = 1f - t
                                        1f - remaining * remaining * remaining
                                    }))
                                } finally {
                                    // A returning row may already own a newer animation for this key.
                                    if (animations[key] === progress) {
                                        animations.remove(key)
                                        jobs.remove(key)
                                    }
                                }
                            }
                            jobs[key] = job
                            job.start()
                        }
                    }
                }
            } finally {
                clear()
            }
        }
    }
    return animations
}
