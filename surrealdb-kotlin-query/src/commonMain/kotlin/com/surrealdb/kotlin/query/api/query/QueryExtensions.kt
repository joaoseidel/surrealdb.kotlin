package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.RecordIdRange
import com.surrealdb.kotlin.core.api.data.Target
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.TableRecord
import kotlinx.serialization.json.JsonElement

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
    build: S.(MergePayload) -> Unit,
): MergeQuery<S> = MergeQuery(this, table, table, buildMergePayload(json, table, build))

public fun <S : Table> QueryContext.merge(
    record: TableRecord<S>,
    build: S.(MergePayload) -> Unit,
): MergeQuery<S> = MergeQuery(this, record.schema, record, buildMergePayload(json, record.schema, build))

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
    build: S.(Patches) -> Unit,
): PatchQuery<S> = PatchQuery(this, table, table, buildPatches(json, table, build))

public fun <S : Table> QueryContext.patch(
    record: TableRecord<S>,
    build: S.(Patches) -> Unit,
): PatchQuery<S> = PatchQuery(this, record.schema, record, buildPatches(json, record.schema, build))

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
        else -> throw IllegalArgumentException("Unsupported query target: ${what::class.simpleName}")
    }
