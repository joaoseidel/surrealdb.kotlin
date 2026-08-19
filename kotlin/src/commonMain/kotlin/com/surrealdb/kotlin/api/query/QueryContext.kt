package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.RecordIdRange
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The context a query runs in: somewhere to send a [BoundQuery], and the
 * [Json] to decode what comes back.
 *
 * [com.surrealdb.kotlin.api.Session] sends on the session.
 * [com.surrealdb.kotlin.api.Transaction] sends the same query with its
 * transaction id at the RPC envelope level, so the server scopes it without an
 * explicit `BEGIN` round-trip.
 *
 * Sending is the whole contract. The CRUD surface is written once, as
 * extensions on this interface, so no context can change what `select` means.
 */
public interface QueryContext {
    /** Serializer used to decode query results. */
    public val json: Json

    /** Send a compiled [BoundQuery] via the `query` RPC. */
    public suspend fun query(bound: BoundQuery): JsonElement

    /** Send a raw SurrealQL string with optional name-keyed bindings. */
    public suspend fun query(
        sql: String,
        vars: JsonObject? = null,
    ): JsonElement =
        query(
            BoundQuery(sql).apply {
                vars?.forEach { (name, value) -> attachBinding(name, value) }
            },
        )
}

public fun <T, S : Table<T>> QueryContext.select(table: S): SelectQuery<T, S> = SelectQuery(this, table, table)

public fun QueryContext.select(what: Target): SelectQuery<Nothing, Table<Nothing>> =
    SelectQuery(this, untypedSchema(what), what)

public fun QueryContext.create(what: Target): CreateQuery = CreateQuery(this, what)

public fun <T, S : Table<T>> QueryContext.upsert(table: S): UpsertQuery<T, S> = UpsertQuery(this, table, table)

public fun QueryContext.upsert(what: Target): UpsertQuery<Nothing, Table<Nothing>> =
    UpsertQuery(this, untypedSchema(what), what)

public fun <T, S : Table<T>> QueryContext.update(table: S): UpdateQuery<T, S> = UpdateQuery(this, table, table)

public fun QueryContext.update(what: Target): UpdateQuery<Nothing, Table<Nothing>> =
    UpdateQuery(this, untypedSchema(what), what)

public fun <T, S : Table<T>> QueryContext.merge(
    table: S,
    data: JsonElement,
): MergeQuery<T, S> = MergeQuery(this, table, table, data)

public fun QueryContext.merge(
    what: Target,
    data: JsonElement,
): MergeQuery<Nothing, Table<Nothing>> = MergeQuery(this, untypedSchema(what), what, data)

public fun <T, S : Table<T>> QueryContext.patch(
    table: S,
    patches: JsonElement,
    diff: Boolean = false,
): PatchQuery<T, S> = PatchQuery(this, table, table, patches, diff)

public fun QueryContext.patch(
    what: Target,
    patches: JsonElement,
    diff: Boolean = false,
): PatchQuery<Nothing, Table<Nothing>> = PatchQuery(this, untypedSchema(what), what, patches, diff)

public fun <T, S : Table<T>> QueryContext.delete(table: S): DeleteQuery<T, S> = DeleteQuery(this, table, table)

public fun QueryContext.delete(what: Target): DeleteQuery<Nothing, Table<Nothing>> =
    DeleteQuery(this, untypedSchema(what), what)

public fun QueryContext.relate(
    `in`: Target,
    relation: Target,
    out: Target,
): RelateQuery = RelateQuery(this, `in`, relation, out)

public fun QueryContext.insert(
    into: Table<*>,
    data: JsonElement,
): InsertQuery = InsertQuery(this, into, data)

public fun QueryContext.insertRelation(
    into: Table<*>,
    data: JsonElement,
): InsertRelationQuery = InsertRelationQuery(this, into, data)

public fun QueryContext.run(function: String): RunQuery = RunQuery(this, function)

internal fun untypedSchema(what: Target): Table<Nothing> =
    when (what) {
        is Table<*> -> Table(what.tableName)
        is RecordId -> Table(what.table)
        is RecordIdRange -> Table(what.table)
    }
