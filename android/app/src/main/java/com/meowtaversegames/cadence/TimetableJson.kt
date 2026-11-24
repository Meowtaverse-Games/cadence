package com.meowtaversegames.cadence

import com.meowtaversegames.cadence.core.TimetableValue
import kotlinx.serialization.json.*

/** JSON primitives retain their numeric lexemes; never pass through Double or org.json. */
object TimetableJson {
    fun decode(text: String): TimetableValue = fromJson(Json.parseToJsonElement(text))

    fun encode(value: TimetableValue): String = when (value) {
        is TimetableValue.ObjectValue -> value.fields.entries.joinToString(",", "{", "}") {
            "${JsonPrimitive(it.key)}:${encode(it.value)}"
        }
        is TimetableValue.ArrayValue -> value.values.joinToString(",", "[", "]") { encode(it) }
        is TimetableValue.StringValue -> JsonPrimitive(value.value).toString()
        is TimetableValue.NumberValue -> value.raw
        is TimetableValue.BooleanValue -> value.value.toString()
        TimetableValue.NullValue -> "null"
    }

    private fun fromJson(value: JsonElement): TimetableValue = when (value) {
        JsonNull -> TimetableValue.NullValue
        is JsonObject -> TimetableValue.ObjectValue(value.mapValues { fromJson(it.value) })
        is JsonArray -> TimetableValue.ArrayValue(value.map(::fromJson))
        is JsonPrimitive -> when {
            value.isString -> TimetableValue.StringValue(value.content)
            value.booleanOrNull != null -> TimetableValue.BooleanValue(requireNotNull(value.booleanOrNull))
            else -> TimetableValue.NumberValue(value.content)
        }
    }
}

/** A failed read is not the same as an absent file, including before the first good read. */
class LastGoodValue<T> {
    private var last: T? = null
    fun read(load: () -> T): ReadValue<T> = try {
        val value = load()
        last = value
        ReadValue(value, false)
    } catch (_: Exception) {
        ReadValue(last, true)
    }
}
data class ReadValue<T>(val value: T?, val failed: Boolean)
