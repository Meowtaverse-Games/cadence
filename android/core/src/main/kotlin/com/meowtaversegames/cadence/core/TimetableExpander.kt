package com.meowtaversegames.cadence.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

object TimetableExpander {
    private val japanOffset = ZoneOffset.ofHours(9)

    /** Whole block intervals intersecting [from, to), ordered by start.
     * Empty ranges yield no blocks; reversed ranges throw IllegalArgumentException.
     * Windows and intervals are never merged across different windows or patterns.
     */
    fun expand(timetable: Timetable, from: Instant, to: Instant): List<BlockInterval> {
        require(from <= to) { "The range end must not precede its start." }
        if (from == to) return emptyList()
        var date = from.atOffset(japanOffset).toLocalDate().minusDays(1)
        val lastDate = to.atOffset(japanOffset).toLocalDate()
        val result = mutableListOf<BlockInterval>()

        fun addBlock(start: Instant, end: Instant) {
            if (Duration.between(start, end) >= Duration.ofMinutes(15) && start < to && end > from) {
                result.add(BlockInterval(start, end))
            }
        }

        while (true) {
            for (pattern in timetable.patterns) {
                if (!pattern.enabled || date.dayOfWeek.value !in pattern.weekdays) continue
                val windowStart = date.atStartOfDay().toInstant(japanOffset)
                    .plusSeconds(pattern.startMinute * 60L)
                val windowEnd = windowStart.plusSeconds(pattern.durationMinutes * 60L)
                if (windowStart >= to || windowEnd <= from) continue
                var cursor = windowStart
                var index = 0
                var blockStart: Instant? = null
                while (cursor < windowEnd) {
                    val segment = pattern.segments[index]
                    val end = minOf(cursor.plusSeconds(segment.minutes * 60L), windowEnd)
                    if (segment.isBlock) {
                        if (blockStart == null) blockStart = cursor
                    } else if (blockStart != null) {
                        addBlock(blockStart, cursor)
                        blockStart = null
                    }
                    cursor = end
                    index = (index + 1) % pattern.segments.size
                }
                if (blockStart != null) addBlock(blockStart, windowEnd)
            }
            if (date == lastDate) break
            date = date.plusDays(1)
        }
        return result.sortedBy { it.start }
    }

    /** Uses an explicitly supplied instant, without reading the system clock. */
    fun isBlockedAt(timetable: Timetable, now: Instant): Boolean =
        expand(timetable, now, now.plusNanos(1)).isNotEmpty()
}
