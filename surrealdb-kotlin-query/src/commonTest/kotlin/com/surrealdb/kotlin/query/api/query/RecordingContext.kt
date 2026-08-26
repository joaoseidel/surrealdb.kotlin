package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.Row
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

internal class RecordingContext(
    override val json: Json = Json,
    private val result: JsonElement = JsonNull,
) : QueryContext {
    val sent: MutableList<BoundQuery> = mutableListOf()

    override suspend fun queryValues(bound: BoundQuery): List<Row> {
        sent += bound
        return Row.fromResult(json, result)
    }
}
