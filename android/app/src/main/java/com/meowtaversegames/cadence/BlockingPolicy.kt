package com.meowtaversegames.cadence

import com.meowtaversegames.cadence.core.BlockInterval
import java.time.Instant

/** Pure policy. Adjacent windows remain blocked, with no stop/start at their shared edge. */
data class BlockingDecision(val runService: Boolean, val end: Instant?, val nextBoundary: Instant?)

fun decideBlocking(now: Instant, intervals: List<BlockInterval>, targets: Set<String>): BlockingDecision {
    val active = intervals.filter { now in it }
    var end = active.maxOfOrNull { it.end }
    if (end != null) {
        for (interval in intervals.sortedBy { it.start }) {
            if (interval.start <= end && interval.end > end) end = interval.end
        }
    }
    val next = intervals.flatMap { listOf(it.start, it.end) }.filter { it > now }.minOrNull()
    return BlockingDecision(active.isNotEmpty() && targets.isNotEmpty(), end, next)
}

enum class ActivityEventType { RESUMED, PAUSED, STOPPED }
data class ActivityKey(val packageName: String, val className: String)
data class ActivityEvent(val time: Long, val key: ActivityKey, val type: ActivityEventType)
data class ActivityState(val resumed: Boolean, val last: ActivityEvent)
data class ForegroundState(
    val activities: Map<ActivityKey, ActivityState> = emptyMap(),
    val seen: Set<ActivityEvent> = emptySet(),
)
data class BlockWindow(val resumed: Boolean = false, val multiWindow: Boolean = false)
data class ForegroundResult(val state: ForegroundState, val packages: Set<String>)

fun usageQueryStart(now: Long, bootTime: Long, previousRead: Long?): Long =
    if (previousRead == null) maxOf(bootTime, now - 86_400_000L)
    else maxOf(bootTime, minOf(previousRead, now) - 10_000L)

/** Deduplicate overlapping queries, preserving order at identical timestamps. */
fun reduceForeground(
    previous: ForegroundState,
    events: List<ActivityEvent>,
    now: Long,
    targets: Set<String>,
    block: BlockWindow,
): ForegroundResult {
    val states = previous.activities.toMutableMap()
    val seen = previous.seen.toMutableSet()
    for (event in events.sortedBy { it.time }) {
        if (!seen.add(event)) continue
        val old = states[event.key]
        if (old != null && event.time < old.last.time) continue
        val resumed = when (event.type) {
            ActivityEventType.RESUMED -> true
            ActivityEventType.PAUSED -> false
            ActivityEventType.STOPPED -> old?.resumed == true && old.last.type != ActivityEventType.PAUSED
        }
        // An old instance's STOPPED must not replace the last RESUMED event.
        if (event.type != ActivityEventType.STOPPED || old?.last?.type == ActivityEventType.PAUSED) {
            states[event.key] = ActivityState(resumed, event)
        }
    }
    if (block.resumed && !block.multiWindow) {
        states.replaceAll { key, state ->
            if (key.packageName in targets) state.copy(resumed = false) else state
        }
    }
    // Retain resumed classes regardless of age: a long-running game may emit no new event.
    val retained = states.filterValues { it.resumed || it.last.time >= now - 10_000L }
    return ForegroundResult(
        ForegroundState(retained, seen.filterTo(mutableSetOf()) { it.time >= now - 10_000L }),
        retained.filterValues { it.resumed }.keys.mapTo(mutableSetOf()) { it.packageName },
    )
}

data class LaunchStatus(val firstAttempt: Long? = null, val failed: Boolean = false)
fun updateLaunchStatus(old: LaunchStatus, elapsed: Long, resumed: Boolean, shouldLaunch: Boolean): LaunchStatus = when {
    resumed -> LaunchStatus()
    old.firstAttempt != null && elapsed - old.firstAttempt >= 3_000 -> old.copy(failed = true)
    shouldLaunch && old.firstAttempt == null -> old.copy(firstAttempt = elapsed)
    else -> old
}

enum class ReconcileSource { ALARM, SYSTEM_BROADCAST, VISIBLE_ACTIVITY, DATA_CHANGED, SERVICE }
enum class ServicePhase { STOPPED, START_REQUESTED, RUNNING }
enum class ServiceAction { START_DIRECTLY, IMMEDIATE_ALARM, KEEP, STOP }

/** KEEP after an alarm denial means no immediate re-reservation; boundary scheduling is independent. */
fun serviceAction(
    shouldRun: Boolean,
    source: ReconcileSource,
    phase: ServicePhase,
    startDenied: Boolean = false,
): ServiceAction = when {
    startDenied -> if (source == ReconcileSource.ALARM) ServiceAction.KEEP else ServiceAction.IMMEDIATE_ALARM
    // Never cancel a pending startForegroundService before the service fulfils its contract.
    phase == ServicePhase.START_REQUESTED -> ServiceAction.KEEP
    !shouldRun -> ServiceAction.STOP
    phase == ServicePhase.RUNNING -> ServiceAction.KEEP
    source == ReconcileSource.DATA_CHANGED -> ServiceAction.IMMEDIATE_ALARM
    source == ReconcileSource.SERVICE -> ServiceAction.KEEP
    else -> ServiceAction.START_DIRECTLY
}

/** Lifecycle callbacks must not evict events that the next overlapping query will read again. */
fun acknowledgeBlockWindow(
    previous: ForegroundState,
    previousRead: Long?,
    targets: Set<String>,
    block: BlockWindow,
): ForegroundState = reduceForeground(previous, emptyList(), previousRead ?: Long.MIN_VALUE + 10_000L,
    targets, block).state

/** A query ends before any lifecycle acknowledgements received while it is in flight.
 * Apply those acknowledgements to the returned batch even if BlockActivity has since paused. */
fun completeForegroundQuery(
    previous: ForegroundState,
    events: List<ActivityEvent>,
    readTime: Long,
    targets: Set<String>,
    block: BlockWindow,
    acknowledgedTargets: Set<String>,
): ForegroundResult {
    val result = reduceForeground(previous, events, readTime, targets, block)
    return reduceForeground(result.state, emptyList(), readTime, acknowledgedTargets, BlockWindow(true, false))
}
