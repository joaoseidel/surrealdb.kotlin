package com.surrealdb.kotlin.api.data

import kotlinx.serialization.json.JsonElement

/**
 * A `WHERE` clause.
 *
 * Build one with the operators on [Table]; the variants are not constructible
 * from outside.
 */
public sealed interface Condition

/**
 * A condition that may appear on either side of `and`.
 *
 * Kotlin gives every infix function one precedence level and associates left to
 * right, so `a eq 1 or b eq 2 and c eq 3` parses as `((a = 1) OR (b = 2)) AND
 * (c = 3)` — while the same text in SurrealQL means `(a = 1) OR ((b = 2) AND
 * (c = 3))`, because SurrealQL binds `AND` tighter.
 *
 * Nest with [Table.all] / [Table.any] / [Table.none].
 */
public sealed interface Conjunctible : Condition

/** A condition that may appear on either side of `or`. See [Conjunctible]. */
public sealed interface Disjunctible : Condition

/**
 * A condition with no top-level `and` or `or` of its own — a comparison, a
 * presence test, a raw fragment, a negation, or a group built by `all` / `any` /
 * `none`.
 */
public sealed interface Atom :
    Conjunctible,
    Disjunctible

internal class Comparison(
    val field: Field<*>,
    val op: String,
    val operand: Any?,
) : Atom

internal class FieldTest(
    val field: Field<*>,
    val op: String,
) : Atom

internal class FunctionCall(
    val function: String,
    val field: Field<*>,
    val operand: Any?,
) : Atom

internal class RawCondition(
    val sql: String,
    val bindings: Map<String, JsonElement>,
) : Atom

internal class Negation(
    val inner: Condition,
) : Atom

internal class Grouped(
    val inner: Condition,
) : Atom

internal class Conjunction(
    val parts: List<Condition>,
) : Conjunctible

internal class Disjunction(
    val parts: List<Condition>,
) : Disjunctible
