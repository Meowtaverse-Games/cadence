package com.meowtaversegames.cadence

/** A generation identifies every jump, including a jump requested while already suppressed. */
data class EntranceSuppression(val generation: Long = 0, val suppressed: Boolean = true) {
    fun request() = EntranceSuppression(generation + 1, true)
    fun afterIdleFrame(observedGeneration: Long, scrolling: Boolean): EntranceSuppression =
        if (observedGeneration == generation && !scrolling) copy(suppressed = false) else this
}

data class EntranceFrame(
    val rows: List<EntranceRow>,
    val orderedKeys: List<String>,
    val scrolling: Boolean,
    val enabled: Boolean = true,
    val suppressed: Boolean = false,
    val generation: Long = 0,
    val measurement: Long = 0,
)

data class EntranceChanges(
    val start: List<String> = emptyList(),
    val remove: Set<String> = emptySet(),
    val clearAll: Boolean = false,
)

/** Pure visibility state machine; neither Compose objects nor row contents affect identity. */
class EntranceTracker {
    private var previous: Set<String>? = null
    private var orderedKeys: List<String>? = null
    private var generation: Long? = null
    private var measurement: Long? = null
    private var pendingMeasurement: Long? = null
    private val active = mutableSetOf<String>()
    val activeKeys: Set<String> get() = active.toSet()

    fun completed(key: String) { active.remove(key) }

    fun observe(frame: EntranceFrame): EntranceChanges {
        val orderChanged = orderedKeys != null && orderedKeys != frame.orderedKeys
        val generationChanged = generation != frame.generation
        // A new item provider may precede its measurement. Suppress that measurement once,
        // never wait for every visible index to match (sticky/reused layout data may lag).
        if (orderChanged || generationChanged) {
            pendingMeasurement = frame.measurement.takeIf { it == measurement }
        }
        val reset = previous == null || orderChanged || generationChanged || pendingMeasurement != null
        if (pendingMeasurement != null &&
            (pendingMeasurement != frame.measurement || (!frame.suppressed && !frame.scrolling))) {
            pendingMeasurement = null
        }
        val current = frame.rows.mapTo(mutableSetOf()) { it.key }
        val clearAll = reset || !frame.enabled || frame.suppressed
        val start = enteringKeys(previous, frame.rows, frame.scrolling, frame.enabled, frame.suppressed, reset)
        val remove = active - current
        if (clearAll) active.clear() else active.removeAll(remove)
        active.addAll(start)
        previous = current
        orderedKeys = frame.orderedKeys.toList()
        generation = frame.generation
        measurement = frame.measurement
        return EntranceChanges(start, remove, clearAll)
    }
}
