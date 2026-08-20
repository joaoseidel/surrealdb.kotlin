package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Atom
import com.surrealdb.kotlin.api.data.Comparison
import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Conjunction
import com.surrealdb.kotlin.api.data.Disjunction
import com.surrealdb.kotlin.api.data.Field
import com.surrealdb.kotlin.api.data.FieldTest
import com.surrealdb.kotlin.api.data.FunctionCall
import com.surrealdb.kotlin.api.data.Grouped
import com.surrealdb.kotlin.api.data.Negation
import com.surrealdb.kotlin.api.data.RawCondition
import com.surrealdb.kotlin.api.data.Table

internal fun BoundQuery.appendCondition(condition: Condition): BoundQuery =
    apply {
        when (condition) {
            is Comparison -> {
                appendLiteral("(")
                appendLiteral(condition.field.path)
                appendLiteral(" ${condition.op} ")
                appendOperand(condition.operand)
                appendLiteral(")")
            }

            is FieldTest -> {
                appendLiteral("(")
                appendLiteral(condition.field.path)
                appendLiteral(" ${condition.op})")
            }

            is FunctionCall -> {
                appendLiteral("${condition.function}(")
                appendLiteral(condition.field.path)
                appendLiteral(", ")
                appendOperand(condition.operand)
                appendLiteral(")")
            }

            is RawCondition -> {
                appendLiteral("(")
                appendFragment(condition.sql, condition.bindings)
                appendLiteral(")")
            }

            is Grouped -> {
                appendCondition(condition.inner)
            }

            is Conjunction -> {
                appendJoined(condition.parts, " AND ")
            }

            is Disjunction -> {
                appendJoined(condition.parts, " OR ")
            }

            is Negation -> {
                appendLiteral("!")
                appendCondition(condition.inner)
            }
        }
    }

private fun BoundQuery.appendJoined(
    parts: List<Condition>,
    joiner: String,
) {
    appendLiteral("(")
    parts.forEachIndexed { index, part ->
        if (index > 0) appendLiteral(joiner)
        appendCondition(part)
    }
    appendLiteral(")")
}

private fun BoundQuery.appendOperand(operand: Any?) {
    when (operand) {
        is Field<*> -> appendLiteral(operand.path)
        else -> appendValue(operand)
    }
}

/** The SurrealQL this condition renders to, with its bindings. */
public fun Condition.toSurql(): BoundQuery = BoundQuery().appendCondition(this)

/**
 * The escape hatch, for what has no Kotlin-side name: a server-side computed
 * field, or an operator the DSL does not model. Interpolated values are bound
 * as parameters, the same rule as everywhere else.
 *
 * ```
 * where { raw { +"geo::distance(location, "; value(here); +") < "; value(radius) } }
 * ```
 */
public fun Table.raw(block: SurqlBuilder.() -> Unit): Atom {
    val fragment = surql(block)
    return RawCondition(fragment.surql, fragment.bindings)
}
