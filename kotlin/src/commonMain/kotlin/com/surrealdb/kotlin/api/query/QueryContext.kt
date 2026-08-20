package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.RecordIdRange
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.TableRecord
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

/**
 * Every verb comes in three overloads: a declared table, one record of a
 * declared table, and a bare [Target]. The first two carry the declaration
 * through, so `where { }` and `set { }` still name fields. The third is what a
 * [RecordId] or a [RecordIdRange] lands on, and it has no fields to name.
 */
public fun <S : Table> QueryContext.select(table: S): SelectQuery<S> = SelectQuery(this, table, table)

public fun <S : Table> QueryContext.select(record: TableRecord<S>): SelectQuery<S> =
    SelectQuery(this, record.schema, record)

public fun QueryContext.select(what: Target): SelectQuery<Table> = SelectQuery(this, schemaOf(what), what)

public fun <S : Table> QueryContext.create(table: S): CreateQuery<S> = CreateQuery(this, table, table)

public fun <S : Table> QueryContext.create(record: TableRecord<S>): CreateQuery<S> =
    CreateQuery(this, record.schema, record)

public fun QueryContext.create(what: Target): CreateQuery<Table> = CreateQuery(this, schemaOf(what), what)

public fun <S : Table> QueryContext.upsert(table: S): UpsertQuery<S> = UpsertQuery(this, table, table)

public fun <S : Table> QueryContext.upsert(record: TableRecord<S>): UpsertQuery<S> =
    UpsertQuery(this, record.schema, record)

public fun QueryContext.upsert(what: Target): UpsertQuery<Table> = UpsertQuery(this, schemaOf(what), what)

public fun <S : Table> QueryContext.update(table: S): UpdateQuery<S> = UpdateQuery(this, table, table)

public fun <S : Table> QueryContext.update(record: TableRecord<S>): UpdateQuery<S> =
    UpdateQuery(this, record.schema, record)

public fun QueryContext.update(what: Target): UpdateQuery<Table> = UpdateQuery(this, schemaOf(what), what)

public fun <S : Table> QueryContext.merge(
    table: S,
    data: JsonElement,
): MergeQuery<S> = MergeQuery(this, table, table, data)

public fun <S : Table> QueryContext.merge(
    record: TableRecord<S>,
    data: JsonElement,
): MergeQuery<S> = MergeQuery(this, record.schema, record, data)

public fun QueryContext.merge(
    what: Target,
    data: JsonElement,
): MergeQuery<Table> = MergeQuery(this, schemaOf(what), what, data)

public fun <S : Table> QueryContext.patch(
    table: S,
    patches: JsonElement,
): PatchQuery<S> = PatchQuery(this, table, table, patches)

public fun <S : Table> QueryContext.patch(
    record: TableRecord<S>,
    patches: JsonElement,
): PatchQuery<S> = PatchQuery(this, record.schema, record, patches)

public fun QueryContext.patch(
    what: Target,
    patches: JsonElement,
): PatchQuery<Table> = PatchQuery(this, schemaOf(what), what, patches)

public fun <S : Table> QueryContext.delete(table: S): DeleteQuery<S> = DeleteQuery(this, table, table)

public fun <S : Table> QueryContext.delete(record: TableRecord<S>): DeleteQuery<S> =
    DeleteQuery(this, record.schema, record)

public fun QueryContext.delete(what: Target): DeleteQuery<Table> = DeleteQuery(this, schemaOf(what), what)

public fun QueryContext.relate(
    `in`: Target,
    relation: Target,
    out: Target,
): RelateQuery = RelateQuery(this, `in`, relation, out)

public fun QueryContext.insert(
    into: Table,
    data: JsonElement,
): InsertQuery = InsertQuery(this, into, data)

public fun QueryContext.insertRelation(
    into: Table,
    data: JsonElement,
): InsertRelationQuery = InsertRelationQuery(this, into, data)

public fun QueryContext.run(function: String): RunQuery = RunQuery(this, function)

internal fun schemaOf(what: Target): Table =
    when (what) {
        is Table -> what
        is RecordId -> Table(what.table)
        is TableRecord<*> -> what.schema
        is RecordIdRange -> Table(what.table)
    }
