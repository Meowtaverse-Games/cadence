package com.meowtaversegames.cadence.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** One JUnit case per shared file; newly added fixtures are discovered automatically. */
@RunWith(Parameterized::class)
class TimetableFixtureTest(private val name: String, private val file: Path) {
    @Test
    fun matchesSharedFixture() {
        val fixture = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(name, fixture.getValue("name").jsonPrimitive.content)
        val expected = fixture.getValue("expected").jsonObject
        val result = TimetableParser.parse(fixture.getValue("timetable").toTimetableValue())
        assertEquals(expected.getValue("valid").jsonPrimitive.boolean, result.valid)
        if (!result.valid) {
            assertNull(result.timetable)
            assertEquals(expected.getValue("error").jsonPrimitive.content, result.errorCode)
            return
        }
        assertNull(result.errorCode)
        assertNotNull(result.timetable)
        val intervals = expected.getValue("intervals").jsonArray.map { value ->
            val interval = value.jsonObject
            BlockInterval(
                Instant.parse(interval.getValue("start").jsonPrimitive.content),
                Instant.parse(interval.getValue("end").jsonPrimitive.content),
            )
        }
        assertEquals(intervals, TimetableExpander.expand(
            requireNotNull(result.timetable),
            Instant.parse(fixture.getValue("from").jsonPrimitive.content),
            Instant.parse(fixture.getValue("to").jsonPrimitive.content),
        ))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> {
            val directory = Path.of(requireNotNull(System.getProperty("cadence.fixtures")))
            val files = Files.list(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .sorted().toList()
            }
            require(files.isNotEmpty()) { "No shared fixtures found in $directory" }
            return files.map { arrayOf(it.fileName.toString().removeSuffix(".json"), it) }
        }
    }
}

/** The test adapter preserves numeric lexemes (including 10.0 and exponent notation). */
internal fun JsonElement.toTimetableValue(): TimetableValue = when (this) {
    JsonNull -> TimetableValue.NullValue
    is JsonObject -> TimetableValue.ObjectValue(mapValues { it.value.toTimetableValue() })
    is JsonArray -> TimetableValue.ArrayValue(map { it.toTimetableValue() })
    is JsonPrimitive -> when {
        isString -> TimetableValue.StringValue(content)
        booleanOrNull != null -> TimetableValue.BooleanValue(requireNotNull(booleanOrNull))
        else -> TimetableValue.NumberValue(content)
    }
}
