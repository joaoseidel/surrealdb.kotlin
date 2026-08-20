package com.surrealdb.kotlin.api.data

/**
 * What a statement operates on: a whole table, one record, a range of records
 * on one table, or one record of a table that declared its record type.
 *
 * Every CRUD verb takes a `Target` rather than an `Any`, so `select(42)` and
 * `select("user")`, the latter selecting the *string* `"user"` rather than the
 * table, stop compiling instead of failing at the server. Being sealed also
 * means the builders share one renderer the compiler checks is exhaustive, so a
 * new kind of target cannot be added without every verb being told about it.
 *
 * The kinds implement this directly rather than being wrapped by it, so
 * `select(Table("user"))` reads the same as it always did.
 */
public sealed interface Target

/**
 * A SurrealDB record id (e.g. `user:alice`).
 *
 * Both `table` and `id` are surfaced verbatim; the builder emits the pair via
 * `type::record($_tb, $_id)` (SurrealDB v3) with both halves bound.
 */
public data class RecordId(
    public val table: String,
    public val id: String,
) : Target {
    override fun toString(): String = "$table:$id"
}

/**
 * A range of record ids on a single table (e.g. `user:alice..user:zara`).
 */
public data class RecordIdRange(
    public val table: String,
    public val start: String? = null,
    public val end: String? = null,
    public val includeEnd: Boolean = false,
) : Target

/**
 * One record of a declared table, carrying the schema that names its fields.
 *
 * `update(RecordId("book", "sicp"))` resolves to the untyped overload and loses
 * every field with it, so a `where { }` beside it has nothing to name. With the
 * schema attached the typed overloads apply to a single record too:
 *
 * ```
 * db.select(Books["sicp"]).await()
 * db.update(Books["sicp"]).where { pages greater 0 }
 * ```
 *
 * It is a [Target] rather than a wrapper around one, so it is also a value.
 * Handed to an expression it writes the link, the same as the [RecordId] it
 * names.
 */
public class TableRecord<T, S : Table<T>> internal constructor(
    internal val schema: S,
    internal val record: RecordId,
) : Target {
    override fun toString(): String = record.toString()

    override fun equals(other: Any?): Boolean = other is TableRecord<*, *> && other.record == record

    override fun hashCode(): Int = record.hashCode()
}

/** Name one record of this table, keeping the schema: `Books["sicp"]`. */
public operator fun <T, S : Table<T>> S.get(id: String): TableRecord<T, S> = TableRecord(this, RecordId(tableName, id))
