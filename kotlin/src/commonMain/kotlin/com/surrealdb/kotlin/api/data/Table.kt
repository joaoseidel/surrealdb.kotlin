package com.surrealdb.kotlin.api.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * A SurrealDB table, and — when declared with its record type — the place that
 * type's fields are named.
 *
 * ```
 * @Serializable
 * data class Book(val title: String, val pages: Int)
 *
 * object Books : Table<Book>("book", Book.serializer()) {
 *     val id = recordId()
 *     val title by field<String>()
 *     val pages by field<Int>()
 * }
 *
 * db.select(Books).where { pages greater 100 }
 * ```
 *
 * The serializer is passed rather than resolved from `T`, because Kotlin cannot
 * pass a reified argument to a supertype constructor — and because taking it in
 * the constructor means the descriptor exists before any `field(...)` in the
 * body runs, which is what lets a misspelt name throw at declaration.
 *
 * `Table("user")` still names a table with no declared fields: it resolves to
 * the same-named function below, not to a constructor, so no type argument has
 * to be inferred from nothing.
 *
 * The comparison operators are **members**, which is what binds their result to
 * `T`: a condition written here cannot be handed to a query over another table.
 *
 * The table's own name is [tableName], not `name`, because a declaration body
 * puts the record's fields in the same scope: `val name by field<String>()` is
 * an ordinary thing to write, and it would otherwise collide with the table's.
 */
public open class Table<T> internal constructor(
    public val tableName: String,
    descriptor: SerialDescriptor?,
) : Fields("", descriptor, descriptor?.let(::labelOf) ?: tableName),
    Target {
    public constructor(name: String, serializer: KSerializer<T>) : this(name, serializer.descriptor)

    /**
     * The record id. SurrealDB fixes it at the field `id`, so it takes no name
     * and is not checked against the descriptor — the id is there whether or
     * not the Kotlin class models it.
     */
    protected fun recordId(): Field<RecordId> = Field(childPath(groupPath, "id"))

    public infix fun <V> Field<V>.eq(value: V): Atom<T> = Comparison(this, "=", value)

    public infix fun <V> Field<V>.eq(other: Field<V>): Atom<T> = Comparison(this, "=", other)

    public infix fun <V> Field<V>.neq(value: V): Atom<T> = Comparison(this, "!=", value)

    public infix fun <V> Field<V>.neq(other: Field<V>): Atom<T> = Comparison(this, "!=", other)

    public infix fun <V : Comparable<V>> Field<V>.greater(value: V): Atom<T> = Comparison(this, ">", value)

    public infix fun <V : Comparable<V>> Field<V>.greaterEq(value: V): Atom<T> = Comparison(this, ">=", value)

    public infix fun <V : Comparable<V>> Field<V>.less(value: V): Atom<T> = Comparison(this, "<", value)

    public infix fun <V : Comparable<V>> Field<V>.lessEq(value: V): Atom<T> = Comparison(this, "<=", value)

    public infix fun <V> Field<V>.inside(values: Collection<V>): Atom<T> = Comparison(this, "IN", values)

    public infix fun <E> Field<List<E>>.contains(value: E): Atom<T> = Comparison(this, "CONTAINS", value)

    public infix fun <E> Field<List<E>>.containsAll(values: Collection<E>): Atom<T> =
        Comparison(this, "CONTAINSALL", values)

    public infix fun <E> Field<List<E>>.containsAny(values: Collection<E>): Atom<T> =
        Comparison(this, "CONTAINSANY", values)

    public infix fun Field<String>.startsWith(prefix: String): Atom<T> = FunctionCall(STARTS_WITH, this, prefix)

    public infix fun Field<String>.matches(pattern: String): Atom<T> = FunctionCall(MATCHES, this, pattern)

    /** SurrealDB distinguishes NONE, NULL and absent, so the DSL does too. */
    public fun Field<*>.isNone(): Atom<T> = FieldTest(this, "IS NONE")

    public fun Field<*>.isNull(): Atom<T> = FieldTest(this, "IS NULL")

    public fun Field<*>.exists(): Atom<T> = FieldTest(this, "IS NOT NONE")

    /**
     * `AND`. Chains freely with itself; mixing it with [or] without an explicit
     * group does not compile, because Kotlin's one infix precedence level would
     * otherwise parse the chain differently from the SurrealQL it generates.
     * See [Conjunctible].
     */
    public infix fun Conjunctible<T>.and(other: Conjunctible<T>): Conjunctible<T> =
        Conjunction(flattenConjunction(this) + flattenConjunction(other))

    /** `OR`. See [and]. */
    public infix fun Disjunctible<T>.or(other: Disjunctible<T>): Disjunctible<T> =
        Disjunction(flattenDisjunction(this) + flattenDisjunction(other))

    public fun not(inner: Condition<T>): Atom<T> = Negation(inner)

    /** Every condition must hold. Takes its operands n-ary, so no binding order applies. */
    public fun all(vararg conditions: Condition<T>): Atom<T> = Grouped(Conjunction(conditions.toRequiredList("all")))

    /** At least one condition must hold. */
    public fun any(vararg conditions: Condition<T>): Atom<T> = Grouped(Disjunction(conditions.toRequiredList("any")))

    /** No condition may hold. */
    public fun none(vararg conditions: Condition<T>): Atom<T> = Negation(Disjunction(conditions.toRequiredList("none")))

    private fun Array<out Condition<T>>.toRequiredList(caller: String): List<Condition<T>> {
        require(isNotEmpty()) { "$caller(...) requires at least one condition" }
        return toList()
    }

    override fun toString(): String = tableName

    override fun equals(other: Any?): Boolean = other is Table<*> && other.tableName == tableName

    override fun hashCode(): Int = tableName.hashCode()

    private companion object {
        const val STARTS_WITH = "string::starts_with"
        const val MATCHES = "string::matches"
    }
}

private fun <T> flattenConjunction(condition: Condition<T>): List<Condition<T>> =
    if (condition is Conjunction<T>) condition.parts else listOf(condition)

private fun <T> flattenDisjunction(condition: Condition<T>): List<Condition<T>> =
    if (condition is Disjunction<T>) condition.parts else listOf(condition)

/**
 * A table with no declared record type — a target only. A `where { }` over one
 * can still be written with `raw { }`, but it has no fields to name.
 */
public fun Table(name: String): Table<Nothing> = Table(name, null as SerialDescriptor?)
