package com.surrealdb.kotlin.query.api.data

import com.surrealdb.kotlin.core.api.data.LiveQueryTarget
import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.Target

/**
 * A SurrealDB table, and the one place its fields are named.
 *
 * ```
 * object Books : Table("book") {
 *     val id = recordId()
 *     val title by field<String>()
 *     val pages by field<Int>()
 * }
 *
 * db.select(Books).where { pages greater 100 }
 * ```
 *
 * The declaration is the whole description the library needs. There is no wire
 * type beside it: a caller with a domain class of their own keeps it and
 * decodes into it, and nothing here has to agree with it. Whether the *database*
 * agrees is asked by [com.surrealdb.kotlin.query.api.query.checkSchema].
 *
 * `Table("book")` on its own is a target for every verb with no fields to name,
 * so a `where { }` over one is written with `raw { }`.
 *
 * The fields of an object field are declared as a [Nested] group.
 */
@SurqlDsl
public open class Table(
    public val tableName: String,
) : FieldGroup(""),
    Target,
    LiveQueryTarget {
    override val liveQueryTableName: String get() = tableName

    /**
     * The record id. SurrealDB fixes it at the field `id`, so it takes no name,
     * and it is not one of the declared fields: the id is there whether or not
     * any schema mentions it.
     */
    protected fun recordId(): Field<RecordId> = Field("id")

    public infix fun <V> Field<V>.eq(value: V): Atom = Comparison(this, "=", value)

    public infix fun <V> Field<V>.eq(other: Field<V>): Atom = Comparison(this, "=", other)

    public infix fun <V> Field<V>.neq(value: V): Atom = Comparison(this, "!=", value)

    public infix fun <V> Field<V>.neq(other: Field<V>): Atom = Comparison(this, "!=", other)

    public infix fun <V : Comparable<V>> Field<V>.greater(value: V): Atom = Comparison(this, ">", value)

    public infix fun <V : Comparable<V>> Field<V>.greaterEq(value: V): Atom = Comparison(this, ">=", value)

    public infix fun <V : Comparable<V>> Field<V>.less(value: V): Atom = Comparison(this, "<", value)

    public infix fun <V : Comparable<V>> Field<V>.lessEq(value: V): Atom = Comparison(this, "<=", value)

    public infix fun <V> Field<V>.inside(values: Collection<V>): Atom = Comparison(this, "IN", values)

    public infix fun <E> Field<List<E>>.contains(value: E): Atom = Comparison(this, "CONTAINS", value)

    public infix fun <E> Field<List<E>>.containsAll(values: Collection<E>): Atom =
        Comparison(this, "CONTAINSALL", values)

    public infix fun <E> Field<List<E>>.containsAny(values: Collection<E>): Atom =
        Comparison(this, "CONTAINSANY", values)

    public infix fun Field<String>.startsWith(prefix: String): Atom = FunctionCall(STARTS_WITH, this, prefix)

    public infix fun Field<String>.matches(pattern: String): Atom = FunctionCall(MATCHES, this, pattern)

    /** SurrealDB distinguishes NONE, NULL and absent, so the DSL does too. */
    public fun Field<*>.isNone(): Atom = FieldTest(this, "IS NONE")

    public fun Field<*>.isNull(): Atom = FieldTest(this, "IS NULL")

    public fun Field<*>.exists(): Atom = FieldTest(this, "IS NOT NONE")

    /** `AND`. Mixing with [or] without an explicit group does not compile; see [Conjunctible]. */
    public infix fun Conjunctible.and(other: Conjunctible): Conjunctible =
        Conjunction(flattenConjunction(this) + flattenConjunction(other))

    /** `OR`. See [and]. */
    public infix fun Disjunctible.or(other: Disjunctible): Disjunctible =
        Disjunction(flattenDisjunction(this) + flattenDisjunction(other))

    public fun not(inner: Condition): Atom = Negation(inner)

    /** Every condition must hold. */
    public fun all(vararg conditions: Condition): Atom = Grouped(Conjunction(conditions.toRequiredList("all")))

    /** At least one condition must hold. */
    public fun any(vararg conditions: Condition): Atom = Grouped(Disjunction(conditions.toRequiredList("any")))

    /** No condition may hold. */
    public fun none(vararg conditions: Condition): Atom = Negation(Disjunction(conditions.toRequiredList("none")))

    private fun Array<out Condition>.toRequiredList(caller: String): List<Condition> {
        require(isNotEmpty()) { "$caller(...) requires at least one condition" }
        return toList()
    }

    override fun toString(): String = tableName

    override fun equals(other: Any?): Boolean = other is Table && other.tableName == tableName

    override fun hashCode(): Int = tableName.hashCode()

    private companion object {
        const val STARTS_WITH = "string::starts_with"
        const val MATCHES = "string::matches"
    }
}

private fun flattenConjunction(condition: Condition): List<Condition> =
    if (condition is Conjunction) condition.parts else listOf(condition)

private fun flattenDisjunction(condition: Condition): List<Condition> =
    if (condition is Disjunction) condition.parts else listOf(condition)
