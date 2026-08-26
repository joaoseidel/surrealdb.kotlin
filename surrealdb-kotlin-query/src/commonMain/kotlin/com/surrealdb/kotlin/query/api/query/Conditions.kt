package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.query.api.data.Atom
import com.surrealdb.kotlin.query.api.data.Condition
import com.surrealdb.kotlin.query.api.data.RawCondition
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.compileCondition

internal fun BoundQuery.appendCondition(condition: Condition): BoundQuery =
    compileCondition(condition).let {
        appendFragment(it.surql, it.bindings)
    }

/** The SurrealQL this condition renders to, with its bindings. */
public fun Condition.toSurQL(): BoundQuery =
    BoundQuery()
        .appendCondition(this)

/**
 * The escape hatch, for what has no Kotlin-side name: a server-side computed
 * field, or an operator the DSL does not model. Interpolated values are bound
 * as parameters, the same rule as everywhere else.
 *
 * `where { raw { "geo::distance(location, ${bind(here)}) < ${bind(radius)}" } }`
 */
public fun Table.raw(block: SurqlTemplate.() -> String): Atom {
    val fragment = surqlTemplate(block)
    return RawCondition(fragment.surql, fragment.bindings)
}
