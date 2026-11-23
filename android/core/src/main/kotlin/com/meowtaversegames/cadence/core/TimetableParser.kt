package com.meowtaversegames.cadence.core

import com.meowtaversegames.cadence.core.TimetableValue.ArrayValue
import com.meowtaversegames.cadence.core.TimetableValue.BooleanValue
import com.meowtaversegames.cadence.core.TimetableValue.NumberValue
import com.meowtaversegames.cadence.core.TimetableValue.ObjectValue
import com.meowtaversegames.cadence.core.TimetableValue.StringValue
import kotlin.math.truncate

object TimetableParser {
    /** Validates all patterns, including disabled ones, in the C# parser's order.
     * Unknown fields are ignored. The caller supplies a decoded value tree, not JSON text.
     */
    fun parse(value: TimetableValue): ValidationResult {
        val root = value as? ObjectValue ?: return invalid("invalid_document")
        if (integer(root["schemaVersion"], 1..1) == null) return invalid("schema_version")
        if (!textEquals(root["timeZone"], "Asia/Tokyo")) return invalid("time_zone")
        val items = (root["patterns"] as? ArrayValue)?.values ?: return invalid("patterns")
        if (items.size > 20) return invalid("patterns")

        val patterns = mutableListOf<Pattern>()
        val ids = mutableSetOf<List<Int>>()
        for (value in items) {
            val item = value as? ObjectValue ?: return invalid("patterns")
            val id = (item["id"] as? StringValue)?.value ?: return invalid("pattern_id")
            val idPoints = id.codePoints().toArray().toList()
            if (idPoints.isEmpty() || !ids.add(idPoints)) return invalid("pattern_id")
            val name = (item["name"] as? StringValue)?.value ?: return invalid("pattern_name")
            if (name.codePoints().count() !in 1L..40L) return invalid("pattern_name")
            val enabled = (item["enabled"] as? BooleanValue)?.value ?: return invalid("enabled")
            val days = (item["weekdays"] as? ArrayValue)?.values ?: return invalid("weekdays")
            if (days.isEmpty()) return invalid("weekdays")
            val weekdays = mutableSetOf<Int>()
            for (day in days) {
                val weekday = integer(day, 1..7) ?: return invalid("weekdays")
                if (!weekdays.add(weekday)) return invalid("weekdays")
            }
            val start = minute(item["start"]) ?: return invalid("window_time")
            val end = minute(item["end"]) ?: return invalid("window_time")
            val values = (item["segments"] as? ArrayValue)?.values ?: return invalid("segments")
            if (values.size !in 1..20) return invalid("segments")
            val segments = mutableListOf<Segment>()
            for (value in values) {
                val segment = value as? ObjectValue ?: return invalid("segments")
                val isBlock = textEquals(segment["kind"], "block")
                if (!isBlock && !textEquals(segment["kind"], "free")) return invalid("segment_kind")
                val minutes = integer(segment["minutes"], 1..720) ?: return invalid("segment_minutes")
                if (isBlock && minutes < 15) return invalid("block_minutes")
                segments.add(Segment(isBlock, minutes))
            }
            if (enabled && segments.none { it.isBlock }) return invalid("missing_block")
            patterns.add(Pattern(enabled, weekdays.toSet(), start,
                if (end > start) end - start else end - start + 1440, segments.toList()))
        }
        if (overlaps(patterns)) return invalid("window_overlap")
        return ValidationResult(Timetable(patterns.toList()), null)
    }

    private operator fun ObjectValue.get(name: String): TimetableValue? = fields[name]

    private fun textEquals(value: TimetableValue?, expected: String): Boolean =
        value is StringValue && value.value.codePoints().toArray().contentEquals(expected.codePoints().toArray())

    private fun integer(value: TimetableValue?, range: IntRange): Int? {
        if (value !is NumberValue) return null
        // Match C# JsonElement.GetDouble: retain the lexeme in the tree, then use
        // IEEE 754 semantics for validation, checking bounds before narrowing.
        val number = value.raw.toDoubleOrNull() ?: return null
        if (!number.isFinite() || number != truncate(number) ||
            number < range.first || number > range.last) return null
        return number.toInt()
    }

    private fun minute(value: TimetableValue?): Int? {
        val text = (value as? StringValue)?.value ?: return null
        val points = text.codePoints().toArray()
        if (points.size != 5 || points[2] != ':'.code ||
            points[0] !in '0'.code..'2'.code || points[1] !in '0'.code..'9'.code ||
            points[3] !in '0'.code..'5'.code || points[4] !in '0'.code..'9'.code) return null
        val hour = (points[0] - '0'.code) * 10 + points[1] - '0'.code
        if (hour > 23) return null
        return hour * 60 + (points[3] - '0'.code) * 10 + points[4] - '0'.code
    }

    private data class Window(val owner: Int, val start: Int, val end: Int)

    private fun overlaps(patterns: List<Pattern>): Boolean {
        val week = 7 * 1440
        val windows = patterns.flatMapIndexed { owner, pattern ->
            if (!pattern.enabled) emptyList() else pattern.weekdays.map { day ->
                val start = (day - 1) * 1440 + pattern.startMinute
                Window(owner, start, start + pattern.durationMinutes)
            }
        }
        for (i in windows.indices) {
            for (j in i + 1 until windows.size) {
                if (windows[i].owner == windows[j].owner) continue
                for (shift in listOf(-week, 0, week)) {
                    if (windows[i].start < windows[j].end + shift && windows[j].start + shift < windows[i].end) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun invalid(code: String) = ValidationResult(null, code)
}
