package com.meowtaversegames.cadence

import com.google.firebase.Timestamp
import com.meowtaversegames.cadence.core.TimetableValue
import com.meowtaversegames.cadence.core.TimetableValue.*
import java.time.Instant

/** Pure conversion: unsupported SDK values become null, never a plausible string/number. */
fun firestoreValue(value: Any?): TimetableValue = when (value) {
    null -> NullValue
    is String -> StringValue(value)
    is Boolean -> BooleanValue(value)
    is Long -> TimetableValue.number(value)
    is Double -> TimetableValue.number(value)
    is Timestamp -> StringValue(Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong()).toString())
    is Map<*, *> -> if (value.keys.all { it is String }) {
        ObjectValue(value.entries.associate { (key, child) -> key as String to firestoreValue(child) })
    } else NullValue
    is List<*> -> ArrayValue(value.map(::firestoreValue))
    else -> NullValue // GeoPoint, DocumentReference, Blob, and future SDK types.
}

/** Only validated schema fields are cached/compared; timestamps and unknown fields are ignored. */
fun timetableContent(value: TimetableValue): ObjectValue {
    val root = value as ObjectValue
    fun ObjectValue.pick(vararg names: String) = ObjectValue(fields.filterKeys { it in names })
    val patterns = (root.fields.getValue("patterns") as ArrayValue).values.map { item ->
        val pattern = (item as ObjectValue).pick("id", "name", "enabled", "weekdays", "start", "end", "segments")
        val segments = (pattern.fields.getValue("segments") as ArrayValue).values.map {
            (it as ObjectValue).pick("kind", "minutes")
        }
        ObjectValue(pattern.fields + ("segments" to ArrayValue(segments)))
    }
    return ObjectValue(root.pick("schemaVersion", "timeZone").fields + ("patterns" to ArrayValue(patterns)))
}

fun sameTimetableContent(left: TimetableValue, right: TimetableValue): Boolean =
    sameValue(timetableContent(left), timetableContent(right))

private fun sameValue(left: TimetableValue, right: TimetableValue): Boolean = when {
    left is StringValue && right is StringValue ->
        left.value.codePoints().toArray().contentEquals(right.value.codePoints().toArray())
    left is NumberValue && right is NumberValue -> left.raw.toBigDecimal().compareTo(right.raw.toBigDecimal()) == 0
    left is ObjectValue && right is ObjectValue -> left.fields.keys == right.fields.keys &&
        left.fields.all { (key, value) -> sameValue(value, right.fields.getValue(key)) }
    left is ArrayValue && right is ArrayValue -> left.values.size == right.values.size &&
        left.values.indices.all { sameValue(left.values[it], right.values[it]) }
    else -> left == right
}
