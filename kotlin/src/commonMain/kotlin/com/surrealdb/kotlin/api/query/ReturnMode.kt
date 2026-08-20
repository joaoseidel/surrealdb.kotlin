package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Field

/**
 * How a write statement's `RETURN` clause should behave.
 *
 * Maps onto the SurrealQL `RETURN NONE | BEFORE | AFTER | DIFF | <fields>` form.
 */
public sealed class ReturnMode {
    public data object None : ReturnMode()

    public data object Before : ReturnMode()

    public data object After : ReturnMode()

    public data object Diff : ReturnMode()

    public data class Fields(
        val fields: List<Field<*>>,
    ) : ReturnMode() {
        init {
            require(fields.isNotEmpty()) { "ReturnMode.Fields requires at least one field" }
        }
    }

    internal fun render(into: BoundQuery) {
        into.appendLiteral(" RETURN ")
        when (this) {
            None -> into.appendLiteral("NONE")
            Before -> into.appendLiteral("BEFORE")
            After -> into.appendLiteral("AFTER")
            Diff -> into.appendLiteral("DIFF")
            is Fields -> into.appendProjections(fields)
        }
    }
}
