package com.meowtaversegames.cadence

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.GeoPoint
import com.meowtaversegames.cadence.core.TimetableParser
import com.meowtaversegames.cadence.core.TimetableValue
import com.meowtaversegames.cadence.core.TimetableValue.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

internal fun syntheticDocument(name: String = "Synthetic", updatedAt: Any? = null): Map<String, Any?> = mapOf(
    "schemaVersion" to 1L, "timeZone" to "Asia/Tokyo", "updatedAt" to updatedAt,
    "patterns" to listOf(mapOf("id" to "synthetic", "name" to name, "enabled" to true,
        "weekdays" to listOf(1L), "start" to "09:00", "end" to "10:00",
        "segments" to listOf(mapOf("kind" to "block", "minutes" to 15L)))),
)
internal fun syntheticValue(name: String = "Synthetic"): TimetableValue = firestoreValue(syntheticDocument(name))

class FirestoreValuesTest {
    @Test fun primitivesKeepTheirTypesAndPrecision() {
        assertEquals(NumberValue(Long.MAX_VALUE.toString()), firestoreValue(Long.MAX_VALUE))
        assertEquals(NumberValue(Long.MIN_VALUE.toString()), firestoreValue(Long.MIN_VALUE))
        assertEquals(NumberValue("10.0"), firestoreValue(10.0))
        assertEquals(NumberValue("0.125"), firestoreValue(0.125))
        assertEquals(StringValue("synthetic"), firestoreValue("synthetic"))
        assertEquals(BooleanValue(true), firestoreValue(true))
        assertEquals(BooleanValue(false), firestoreValue(false))
        assertEquals(NullValue, firestoreValue(null))
    }

    @Test fun nonFiniteNumbersNeverThrowOrRemainNumbers() {
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(firestoreValue(number) is NumberValue)
            assertEquals("schema_version", TimetableParser.parse(firestoreValue(syntheticDocument() + ("schemaVersion" to number))).errorCode)
            assertTrue(TimetableParser.parse(firestoreValue(syntheticDocument() + ("unknown" to number))).valid)
        }
    }

    @Test fun timestampBecomesAnIsoString() {
        assertEquals(StringValue("2026-01-01T00:00:00.123456789Z"), firestoreValue(Timestamp(1767225600, 123456789)))
    }

    @Test fun nestedAndEmptyContainersStayDistinct() {
        assertEquals(ObjectValue(mapOf("array" to ArrayValue(listOf(
            ObjectValue(emptyMap()), ArrayValue(emptyList()), ObjectValue(mapOf("flag" to BooleanValue(true))),
        )))), firestoreValue(mapOf("array" to listOf(emptyMap<String, Any>(), emptyList<Any>(), mapOf("flag" to true)))))
        assertEquals(NullValue, firestoreValue(mapOf(1 to true)))
    }

    @Test fun unsupportedSdkValuesAreIgnoredOnlyInUnknownFields() {
        val unsupported = listOf(GeoPoint(0.0, 0.0), Blob.fromBytes(byteArrayOf(1)), mock(DocumentReference::class.java), Any())
        for (value in unsupported) {
            assertEquals(NullValue, firestoreValue(value))
            assertTrue(TimetableParser.parse(firestoreValue(syntheticDocument() + ("unknown" to listOf(mapOf("nested" to value))))).valid)
            assertEquals("time_zone", TimetableParser.parse(firestoreValue(syntheticDocument() + ("timeZone" to value))).errorCode)
            assertEquals("patterns", TimetableParser.parse(firestoreValue(syntheticDocument() + ("patterns" to listOf(value)))).errorCode)
        }
    }

    @Test fun decimalIntegersAreValidatedByCore() {
        assertTrue(TimetableParser.parse(firestoreValue(syntheticDocument() + ("schemaVersion" to 1.0))).valid)
        assertEquals("schema_version", TimetableParser.parse(firestoreValue(syntheticDocument() + ("schemaVersion" to 1.5))).errorCode)
        assertTrue(TimetableParser.parse(firestoreValue(syntheticDocument() + ("patterns" to emptyList<Any>()))).valid)
        assertEquals("patterns", TimetableParser.parse(firestoreValue(syntheticDocument() + ("patterns" to emptyMap<String, Any>()))).errorCode)
    }

    @Test fun updatedAtAndUnknownFieldsDoNotChangeContent() {
        val first = firestoreValue(syntheticDocument(updatedAt = Timestamp(0, 0)))
        val second = firestoreValue(syntheticDocument(updatedAt = Timestamp(1, 0)) + ("unknown" to GeoPoint(0.0, 0.0)))
        assertTrue(sameTimetableContent(first, second))
        assertFalse(timetableContent(second).fields.containsKey("updatedAt"))
        assertFalse(timetableContent(second).fields.containsKey("unknown"))
        val pattern = ((second as ObjectValue).fields.getValue("patterns") as ArrayValue).values.single() as ObjectValue
        val nestedUnknown = ObjectValue(second.fields + ("patterns" to ArrayValue(listOf(ObjectValue(pattern.fields + ("unknown" to NullValue))))))
        assertTrue(sameTimetableContent(first, nestedUnknown))
    }

    @Test fun numericRepresentationsCompareByValue() {
        assertTrue(sameTimetableContent(syntheticValue(), firestoreValue(syntheticDocument() + ("schemaVersion" to 1.0))))
    }

    @Test fun comparisonUsesCodePointsWithoutUnicodeNormalization() {
        assertFalse(sameTimetableContent(syntheticValue("\u00e9"), syntheticValue("e\u0301")))
        assertTrue(sameTimetableContent(syntheticValue("\uD83D\uDE00"), syntheticValue(String(Character.toChars(0x1F600)))))
        assertFalse(sameTimetableContent(syntheticValue("A"), syntheticValue("a")))
    }

    @Test fun schemaTimezoneAndPatternOrderParticipateInComparison() {
        val value = syntheticValue() as ObjectValue
        assertFalse(sameTimetableContent(value, ObjectValue(value.fields + ("schemaVersion" to NumberValue("2")))))
        assertFalse(sameTimetableContent(value, ObjectValue(value.fields + ("timeZone" to StringValue("UTC")))))
        val first = (value.fields.getValue("patterns") as ArrayValue).values.single()
        val other = ((syntheticValue("Other") as ObjectValue).fields.getValue("patterns") as ArrayValue).values.single()
        assertFalse(sameTimetableContent(ObjectValue(value.fields + ("patterns" to ArrayValue(listOf(first, other)))),
            ObjectValue(value.fields + ("patterns" to ArrayValue(listOf(other, first))))))
    }

    @Test fun cachedContentRoundTripsWithoutLosingNamesOrPrecision() {
        val content = timetableContent(syntheticValue("e\u0301"))
        assertEquals(content, TimetableJson.decode(TimetableJson.encode(content)))
    }
}
