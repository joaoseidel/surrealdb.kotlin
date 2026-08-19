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

/**
 * A condition that may appear on either side of `and`.
 *
 * Kotlin gives every infix function one precedence level and associates left to
 * right, so `a eq 1 or b eq 2 and c eq 3` parses as `((a = 1) OR (b = 2)) AND
 * (c = 3)` — while the same text in SurrealQL means `(a = 1) OR ((b = 2) AND
 * (c = 3))`, because SurrealQL binds `AND` tighter. The DSL would silently mean
 * something other than the query it generates: no warning, no error, wrong rows.
 *
 * Parentheses cannot fix it — `(a and b) or c` and `a and b or c` have the same
 * expression type, so no signature can take one and reject the other. What the
 * type system *can* express is that the two do not compose directly: `and`
 * yields a [Conjunctible] that is not a [Disjunctible], so a mixed chain does
 * not compile. Nest with [Table.all] / [Table.any] / [Table.none], where no
 * binding order exists to get wrong.
 */
public sealed interface Conjunctible<T> : Condition<T>

/** A condition that may appear on either side of `or`. See [Conjunctible]. */
public sealed interface Disjunctible<T> : Condition<T>

/**
 * A condition with no top-level `and` or `or` of its own — a comparison, a
 * presence test, a raw fragment, a negation, or a group built by `all` / `any` /
 * `none`. It composes either way, which is what makes an explicit group the way
 * to mix the two operators.
 */
public sealed interface Atom<T> :
    Conjunctible<T>,
    Disjunctible<T>

internal class Comparison<T>(
    val field: Field<*>,
    val op: String,
    val operand: Any?,
) : Atom<T>

internal class FieldTest<T>(
    val field: Field<*>,
    val op: String,
) : Atom<T>

internal class FunctionCall<T>(
    val function: String,
    val field: Field<*>,
    val operand: Any?,
) : Atom<T>

internal class RawCondition<T>(
    val sql: String,
    val bindings: Map<String, JsonElement>,
) : Atom<T>

internal class Negation<T>(
    val inner: Condition<T>,
) : Atom<T>

internal class Grouped<T>(
    val inner: Condition<T>,
) : Atom<T>

internal class Conjunction<T>(
    val parts: List<Condition<T>>,
) : Conjunctible<T>

internal class Disjunction<T>(
    val parts: List<Condition<T>>,
) : Disjunctible<T>
