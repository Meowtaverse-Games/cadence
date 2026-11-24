package com.meowtaversegames.cadence

import com.meowtaversegames.cadence.core.TimetableParser
import com.meowtaversegames.cadence.core.TimetableValue
import org.junit.Assert.*
import org.junit.Test

class TimetableJsonTest {
    @Test fun numericLexemesSurviveRoundTripExactly() {
        val text = """{"decimal":10.0,"exponent":1e+01,"large":922337203685477580812345,"negative":-0.0,"tiny":1E-999}"""
        val tree = TimetableJson.decode(text)
        assertEquals(text, TimetableJson.encode(tree))
        assertEquals(tree, TimetableJson.decode(TimetableJson.encode(tree)))
    }
    @Test fun nestedTreeAndEscapedUnicodeRoundTrip() {
        val tree = TimetableValue.ObjectValue(mapOf("\"key\n" to TimetableValue.ArrayValue(listOf(
            TimetableValue.NullValue, TimetableValue.BooleanValue(true), TimetableValue.StringValue("合成 🎵\n\t\\\""),
        ))))
        assertEquals(tree, TimetableJson.decode(TimetableJson.encode(tree)))
    }
    @Test fun validTimetableRemainsValidWithDecimalSchema() {
        val text = """{"schemaVersion":1.0,"timeZone":"Asia/Tokyo","patterns":[]}"""
        assertTrue(TimetableParser.parse(TimetableJson.decode(TimetableJson.encode(TimetableJson.decode(text)))).valid)
        assertEquals(text, TimetableJson.encode(TimetableJson.decode(text)))
    }
    @Test fun corruptJsonIsNotAnEmptyTimetable() {
        assertThrows(IllegalArgumentException::class.java) { TimetableJson.decode("{broken") }
    }
    @Test fun failedFirstReadHasNoUsableValue() {
        assertEquals(ReadValue<String>(null, true), LastGoodValue<String>().read { error("synthetic") })
    }
    @Test fun failedReadRetainsLastGoodValue() {
        val cache = LastGoodValue<Set<String>>()
        cache.read { setOf("synthetic.game") }
        assertEquals(ReadValue(setOf("synthetic.game"), true), cache.read { error("synthetic") })
    }
    @Test fun absentFileIsAValidEmptyValueAndCanReplacePrevious() {
        val cache = LastGoodValue<Set<String>>()
        cache.read { setOf("synthetic.game") }
        assertEquals(ReadValue(emptySet<String>(), false), cache.read { emptySet() })
    }
    @Test fun recoveryAfterFailureClearsWarning() {
        val cache = LastGoodValue<String>()
        cache.read { "old" }
        cache.read { error("synthetic") }
        assertEquals(ReadValue("new", false), cache.read { "new" })
    }
}
