package com.meowtaversegames.cadence.core

import java.time.Instant

/** Immutable validated timetable; obtain through [TimetableParser.parse]. */
class Timetable internal constructor(internal val patterns: List<Pattern>)

internal data class Pattern(
    val enabled: Boolean,
    val weekdays: Set<Int>,
    val startMinute: Int,
    val durationMinutes: Int,
    val segments: List<Segment>,
)

internal data class Segment(val isBlock: Boolean, val minutes: Int)

class ValidationResult internal constructor(val timetable: Timetable?, val errorCode: String?) {
    val valid: Boolean get() = timetable != null
}

/** A half-open interval [start, end), expressed as absolute instants. */
data class BlockInterval(val start: Instant, val end: Instant) {
    operator fun contains(instant: Instant): Boolean = start <= instant && instant < end
}
