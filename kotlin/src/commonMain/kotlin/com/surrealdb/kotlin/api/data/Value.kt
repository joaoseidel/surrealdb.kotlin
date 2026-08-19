package com.surrealdb.kotlin.api.data

/**
 * What a statement operates on: a whole table, one record, or a range of
 * records on one table.
 *
 * Every CRUD verb takes a `Target` rather than an `Any`, so `select(42)` and
 * `select("user")` — the latter selecting the *string* `"user"`, not the table
 * — stop compiling instead of failing at the server. Being sealed also means
 * the builders share one renderer that the compiler checks is exhaustive: a
 * fourth kind of target cannot be added without every verb being told about it.
 *
 * The three kinds implement this directly rather than being wrapped by it, so
 * `select(Table("user"))` reads the same as it always did.
 */
public sealed interface Target

/**
 * A SurrealDB table reference (e.g. `user`).
 *
 * Used as a query target — the builder will emit it via `type::table($_n)` with
 * the name bound as a parameter, never inlined into the SurrealQL string, so
 * user-supplied table names cannot be used for injection.
 */
public data class Table(
    public val name: String,
) : Target {
    override fun toString(): String = name
}

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
