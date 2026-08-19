package com.surrealdb.kotlin.api.data

import kotlinx.serialization.json.JsonElement

/**
 * A `WHERE` clause bound to the record type it was written against, so a
 * condition over `Author` cannot be handed to a query over `Book`.
 *
 * A condition is an immutable value: composable, storable in a `val`, and
 * testable without a connection. It is a tree only — the SurrealQL it renders
 * to lives in `api.query`, which is what keeps this package free of any
 * dependency on the query DSL.
 *
 * Build them with the operators on [Table]; the variants are not constructible
 * from outside.
 */
public sealed interface Condition<T>

internal class Comparison<T>(
    val field: Field<*>,
    val op: String,
    val operand: Any?,
) : Condition<T>

internal class FieldTest<T>(
    val field: Field<*>,
    val op: String,
) : Condition<T>

internal class RawCondition<T>(
    val sql: String,
    val bindings: Map<String, JsonElement>,
) : Condition<T>

internal class Conjunction<T>(
    val parts: List<Condition<T>>,
) : Condition<T>

internal class Disjunction<T>(
    val parts: List<Condition<T>>,
) : Condition<T>

internal class Negation<T>(
    val inner: Condition<T>,
) : Condition<T>

internal class FunctionCall<T>(
    val function: String,
    val field: Field<*>,
    val operand: Any?,
) : Condition<T>
