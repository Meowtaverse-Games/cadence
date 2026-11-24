package com.meowtaversegames.cadence.core

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableApiTest {
    private val from = Instant.parse("2026-01-05T10:00:00Z")
    private val to = Instant.parse("2026-01-05T11:00:00Z")

    private fun document(schema: String = "1", minutes: String = "20"): String = """
        {"schemaVersion": $schema, "timeZone": "Asia/Tokyo", "patterns": [{
          "id": "synthetic", "name": "Synthetic", "enabled": true, "weekdays": [1],
          "start": "19:00", "end": "20:00", "segments": [{"kind": "block", "minutes": $minutes}]
        }]}
    """.trimIndent()

    private fun parse(json: String) = TimetableParser.parse(Json.parseToJsonElement(json).toTimetableValue())

    @Test
    fun numericAdapterRetainsDecimalExponentAndMagnitude() {
        for (lexeme in listOf("10.0", "1.0", "1e3", "123456789012345678901234567890", "1e9999")) {
            val value = Json.parseToJsonElement(lexeme).toTimetableValue() as TimetableValue.NumberValue
            assertEquals(lexeme, value.raw)
        }
    }

    @Test
    fun numericValidationMatchesCSharpDoubleSemantics() {
        assertTrue(parse(document(schema = "1e0", minutes = "2e1")).valid)
        assertTrue(parse(document(schema = "1.0", minutes = "20.0")).valid)
        // C# GetDouble rounds this decimal to 20 before its integer check.
        assertTrue(parse(document(minutes = "20.0000000000000001")).valid)
        for (number in listOf("10.5", "1e3", "1e9999", "123456789012345678901234567890")) {
            assertEquals("segment_minutes", parse(document(minutes = number)).errorCode)
        }
        assertEquals("schema_version", parse(document(schema = "true")).errorCode)
        assertEquals("segment_minutes", parse(document(minutes = "\"20\"")).errorCode)
    }

    @Test
    fun rejectsNonJsonNumericLexemesAtTreeBoundary() {
        for (lexeme in listOf("NaN", "Infinity", " 1", "0x1p0", "01", "1.0f")) {
            assertThrows(IllegalArgumentException::class.java) { TimetableValue.NumberValue(lexeme) }
        }
    }

    @Test
    fun validationPrioritiesMatchCSharp() {
        // Progress through simultaneous violations in the same order as the C# parser.
        val invalid = listOf(
            "schemaVersion" to "2", "timeZone" to "null", "patterns" to "null",
            "id" to "null", "name" to "null", "enabled" to "null", "weekdays" to "null",
            "start" to "null", "segments" to "null", "kind" to "null", "minutes" to "0",
        )
        val errors = listOf("schema_version", "time_zone", "patterns", "pattern_id", "pattern_name",
            "enabled", "weekdays", "window_time", "segments", "segment_kind", "segment_minutes")
        for (index in invalid.indices) {
            var json = document()
            for ((field, replacement) in invalid.drop(index)) {
                json = replaceField(json, field, replacement)
            }
            assertEquals(errors[index], parse(json).errorCode)
        }
    }

    private fun replaceField(json: String, field: String, replacement: String): String {
        // Each target occurs once in this synthetic document; arrays/objects may contain commas.
        val root = Json.parseToJsonElement(json)
        fun replace(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (key, value) ->
                if (key == field) Json.parseToJsonElement(replacement) else replace(value)
            })
            is JsonArray -> JsonArray(element.map { replace(it) })
            else -> element
        }
        return replace(root).toString()
    }

    @Test
    fun explicitInstantUsesHalfOpenBoundariesAndKeepsWholeBlocks() {
        val timetable = requireNotNull(parse(document()).timetable)
        assertFalse(TimetableExpander.isBlockedAt(timetable, from.minusNanos(1)))
        assertTrue(TimetableExpander.isBlockedAt(timetable, from))
        assertTrue(TimetableExpander.isBlockedAt(timetable, to.minusNanos(1)))
        assertFalse(TimetableExpander.isBlockedAt(timetable, to))
        assertEquals(listOf(BlockInterval(from, to)), TimetableExpander.expand(timetable,
            from.plusSeconds(1), from.plusSeconds(2)))
        assertTrue(TimetableExpander.expand(timetable, from, from).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { TimetableExpander.expand(timetable, to, from) }
    }

    @Test
    fun inputMutationDoesNotChangeValidatedTimetable() {
        val root = Json.parseToJsonElement(document()).toTimetableValue() as TimetableValue.ObjectValue
        val fields = root.fields.toMutableMap()
        val timetable = requireNotNull(TimetableParser.parse(TimetableValue.ObjectValue(fields)).timetable)
        fields.clear()
        assertEquals(listOf(BlockInterval(from, to)), TimetableExpander.expand(timetable, from, to))
    }
}
