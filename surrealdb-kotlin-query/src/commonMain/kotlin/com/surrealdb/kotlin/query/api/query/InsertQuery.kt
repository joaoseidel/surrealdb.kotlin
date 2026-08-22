package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Table
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `INSERT INTO <table> $data` queries.
 */
public class InsertQuery internal constructor(
    context: QueryContext,
    private val into: Table,
    private val data: JsonElement,
) : Query(context) {
    override fun compile(): BoundQuery {
        // INSERT INTO requires a table reference; `type::table($tb)` doesn't
        // parse here, but a bound table name does. Bindings still keep user
        // input out of the SurrealQL string.
        val q = BoundQuery()
        q.appendLiteral("INSERT INTO ")
        q.bind(kotlinx.serialization.json.JsonPrimitive(into.tableName))
        q.appendLiteral(" ")
        q.bind(data)
        return q
    }
}

/**
 * Builder for `INSERT RELATION INTO <table> $data` queries.
 */
public class InsertRelationQuery internal constructor(
    context: QueryContext,
    private val into: Table,
    private val data: JsonElement,
) : Query(context) {
    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("INSERT RELATION INTO ")
        q.bind(kotlinx.serialization.json.JsonPrimitive(into.tableName))
        q.appendLiteral(" ")
        q.bind(data)
        return q
    }
}
