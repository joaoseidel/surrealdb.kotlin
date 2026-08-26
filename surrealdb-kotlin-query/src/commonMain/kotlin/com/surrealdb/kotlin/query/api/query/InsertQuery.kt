package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Table
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builder for `INSERT INTO <table> $data` queries.
 */
public class InsertQuery<S : Table> internal constructor(
    context: QueryContext,
    private val into: S,
    private val data: JsonElement? = null,
) : Query(context) {
    public fun content(data: JsonElement): InsertQuery<S> = copy(data = data)

    public fun content(block: S.(ContentPayload) -> Unit): InsertQuery<S> =
        copy(data = buildContentPayload(context.json, into, block))

    override fun compile(): BoundQuery {
        // INSERT INTO requires a table reference; `type::table($tb)` doesn't
        // parse here, but a bound table name does. Bindings still keep user
        // input out of the SurrealQL string.
        val payload = requirePayload(data, into)
        val q = BoundQuery()
        q.appendLiteral("INSERT INTO ")
        q.bind(JsonPrimitive(into.tableName))
        q.appendLiteral(" ")
        q.bind(payload)
        return q
    }

    private fun copy(data: JsonElement? = this.data): InsertQuery<S> = InsertQuery(context, into, data)
}

/**
 * Builder for `INSERT RELATION INTO <table> $data` queries.
 */
public class InsertRelationQuery<S : Table> internal constructor(
    context: QueryContext,
    private val into: S,
    private val data: JsonElement? = null,
) : Query(context) {
    public fun content(data: JsonElement): InsertRelationQuery<S> = copy(data = data)

    public fun content(block: S.(ContentPayload) -> Unit): InsertRelationQuery<S> =
        copy(data = buildContentPayload(context.json, into, block))

    override fun compile(): BoundQuery {
        val payload = requirePayload(data, into)
        val q = BoundQuery()
        q.appendLiteral("INSERT RELATION INTO ")
        q.bind(JsonPrimitive(into.tableName))
        q.appendLiteral(" ")
        q.bind(payload)
        return q
    }

    private fun copy(data: JsonElement? = this.data): InsertRelationQuery<S> = InsertRelationQuery(context, into, data)
}

private fun requirePayload(
    data: JsonElement?,
    into: Table,
): JsonElement =
    requireNotNull(data) {
        "INSERT INTO ${into.tableName} has nothing to insert: name fields with content { }, or pass a JsonElement. " +
            "SurrealDB has no INSERT without a payload, unlike CREATE."
    }
