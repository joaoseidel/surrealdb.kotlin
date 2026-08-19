package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Comparison
import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Conjunction
import com.surrealdb.kotlin.api.data.Disjunction
import com.surrealdb.kotlin.api.data.Field
import com.surrealdb.kotlin.api.data.FieldTest
import com.surrealdb.kotlin.api.data.FunctionCall
import com.surrealdb.kotlin.api.data.Negation
import com.surrealdb.kotlin.api.data.RawCondition
import com.surrealdb.kotlin.api.data.Table

/**
 * Render [condition] into [into].
 *
 * Every condition is compiled here, so what `select` filters on and what
 * `delete` filters on cannot drift, and the `when` is exhaustive over
 * [Condition] — a new kind of condition will not compile until this has been
 * told about it. Same shape as `appendTarget` for query targets.
 *
 * Field paths are the one thing written into the SurrealQL rather than bound:
 * SurrealQL has no parameter form for an identifier. They are validated against
 * the record type's descriptor at declaration and against an identifier pattern
 * at construction. Every operand is bound.
 */
internal fun BoundQuery.appendCondition(condition: Condition<*>): BoundQuery =
    apply {
        when (condition) {
            is Comparison<*> -> {
                appendLiteral("(")
                appendLiteral(condition.field.path)
                appendLiteral(" ${condition.op} ")
                appendOperand(condition.operand)
                appendLiteral(")")
            }

            is FieldTest<*> -> {
                appendLiteral("(")
                appendLiteral(condition.field.path)
                appendLiteral(" ${condition.op})")
            }

            is FunctionCall<*> -> {
                appendLiteral("${condition.function}(")
                appendLiteral(condition.field.path)
                appendLiteral(", ")
                appendOperand(condition.operand)
                appendLiteral(")")
            }

            is RawCondition<*> -> {
                appendLiteral("(")
                appendFragment(condition.sql, condition.bindings)
                appendLiteral(")")
            }

            is Conjunction<*> -> {
                appendJoined(condition.parts, " AND ")
            }

            is Disjunction<*> -> {
                appendJoined(condition.parts, " OR ")
            }

            is Negation<*> -> {
                appendLiteral("!")
                appendCondition(condition.inner)
            }
        }
    }

private fun BoundQuery.appendJoined(
    parts: List<Condition<*>>,
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

/**
 * The SurrealQL this condition renders to, with its bindings — public so
 * operators can be covered by asserting on the generated query rather than by
 * round-tripping each one through a server.
 */
public fun Condition<*>.toSurql(): BoundQuery = BoundQuery().appendCondition(this)

/**
 * The escape hatch, for what has no Kotlin-side name: a server-side computed
 * field, or an operator the DSL does not model. Interpolated values are bound
 * as parameters, the same rule as everywhere else.
 *
 * ```
 * where { raw { +"geo::distance(location, "; value(here); +") < "; value(radius) } }
 * ```
 */
public fun <T> Table<T>.raw(block: SurqlBuilder.() -> Unit): Condition<T> {
    val fragment = surql(block)
    return RawCondition(fragment.surql, fragment.bindings)
}
