package com.meowtaversegames.cadence.core

/** Library-independent input tree. Missing fields and [NullValue] remain distinct. */
sealed interface TimetableValue {
    data class ObjectValue(val fields: Map<String, TimetableValue>) : TimetableValue
    data class ArrayValue(val values: List<TimetableValue>) : TimetableValue
    data class StringValue(val value: String) : TimetableValue
    data class BooleanValue(val value: Boolean) : TimetableValue

    /**
     * Original numeric lexeme, without narrowing to Long or Double on ingestion.
     * Retains decimal/exponent notation and arbitrarily large values. Adapters for
     * Firestore numbers can supply Long.toString() or Double.toString().
     */
    data class NumberValue(val raw: String) : TimetableValue {
        init {
            require(JSON_NUMBER.matches(raw)) { "Expected a JSON numeric lexeme." }
        }

        private companion object {
            val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        }
    }
    data object NullValue : TimetableValue
}
