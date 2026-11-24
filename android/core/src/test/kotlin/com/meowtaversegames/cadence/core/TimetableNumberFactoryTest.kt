package com.meowtaversegames.cadence.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TimetableNumberFactoryTest(
    private val label: String,
    private val input: Number,
    private val minutesError: String?,
) {
    private fun number(): TimetableValue = when (input) {
        is Double -> TimetableValue.number(input)
        is Long -> TimetableValue.number(input)
        else -> error("Unsupported synthetic input")
    }

    private fun document(): TimetableValue.ObjectValue = Json.parseToJsonElement("""
        {"schemaVersion": 1, "timeZone": "Asia/Tokyo", "patterns": [{
          "id": "synthetic", "name": "Synthetic", "enabled": true, "weekdays": [1],
          "start": "19:00", "end": "20:00", "segments": [
            {"kind": "free", "minutes": 10}, {"kind": "block", "minutes": 20}
          ]
        }]}
    """.trimIndent()).toTimetableValue() as TimetableValue.ObjectValue

    @Test
    fun minutesValidateWithoutThrowing() {
        val value = number()
        val expected = if (input is Double && !input.isFinite()) {
            TimetableValue.StringValue(input.toString())
        } else {
            TimetableValue.NumberValue(input.toString())
        }
        assertEquals(label, expected, value)
        val root = document()
        val patterns = root.fields.getValue("patterns") as TimetableValue.ArrayValue
        val pattern = patterns.values.single() as TimetableValue.ObjectValue
        val segments = pattern.fields.getValue("segments") as TimetableValue.ArrayValue
        val free = segments.values.first() as TimetableValue.ObjectValue
        val changedFree = free.copy(fields = free.fields + ("minutes" to value))
        val changedSegments = segments.copy(values = listOf(changedFree, segments.values.last()))
        val changedPattern = pattern.copy(fields = pattern.fields + ("segments" to changedSegments))
        val changedRoot = root.copy(fields = root.fields +
            ("patterns" to TimetableValue.ArrayValue(listOf(changedPattern))))
        val result = TimetableParser.parse(changedRoot)
        assertEquals(label, minutesError, result.errorCode)
        assertEquals(label, minutesError == null, result.valid)
    }

    @Test
    fun unknownFieldIsIgnoredWithoutThrowing() {
        val root = document()
        val result = TimetableParser.parse(root.copy(fields = root.fields + ("unknown" to number())))
        assertTrue(label, result.valid)
        assertEquals(label, null, result.errorCode)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun numbers(): List<Array<Any?>> = listOf(
            arrayOf("NaN", Double.NaN, "segment_minutes"),
            arrayOf("positive infinity", Double.POSITIVE_INFINITY, "segment_minutes"),
            arrayOf("negative infinity", Double.NEGATIVE_INFINITY, "segment_minutes"),
            arrayOf("negative zero", -0.0, "segment_minutes"),
            // Integral decimals are valid for free segments; block segments require 15+ minutes.
            arrayOf("integral decimal", 10.0, null),
            arrayOf("maximum long", Long.MAX_VALUE, "segment_minutes"),
        )
    }
}
