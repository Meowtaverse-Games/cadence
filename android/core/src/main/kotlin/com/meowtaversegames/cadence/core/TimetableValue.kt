package com.meowtaversegames.cadence.core

/** Library-independent input tree. Missing fields and [NullValue] remain distinct. */
sealed interface TimetableValue {
    data class ObjectValue(val fields: Map<String, TimetableValue>) : TimetableValue
    data class ArrayValue(val values: List<TimetableValue>) : TimetableValue
    data class StringValue(val value: String) : TimetableValue
    data class BooleanValue(val value: Boolean) : TimetableValue

    /**
     * Original numeric lexeme, without narrowing to Long or Double on ingestion.
     * Retains decimal/exponent notation and arbitrarily large values. This constructor
     * requires a JSON numeric lexeme. Firestore adapters should use [TimetableValue.number]
     * for Long/Double values so non-finite doubles are represented without throwing.
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

    companion object {
        /** Preserves the exact integer value without narrowing to Double. */
        fun number(value: Long): TimetableValue = NumberValue(value.toString())

        /**
         * Finite doubles remain numeric. Non-finite doubles become strings, matching
         * Firestore conversion in C#: numeric fields reject them as type errors while
         * unknown fields can ignore them. Neither case throws for any Double input.
         */
        fun number(value: Double): TimetableValue =
            if (value.isFinite()) NumberValue(value.toString()) else StringValue(value.toString())
    }
}
