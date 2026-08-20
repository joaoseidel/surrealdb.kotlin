package com.surrealdb.kotlin.api.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A context that records what it was asked to send and answers with the
 * `[{ status, result }]` envelope a real `query` RPC returns.
 */
internal class RecordingContext(
    override val json: Json = Json,
    private val result: JsonElement = JsonPrimitive("ok"),
) : QueryContext {
    val sent: MutableList<BoundQuery> = mutableListOf()

    override suspend fun query(bound: BoundQuery): JsonElement {
        sent += bound
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("status", "OK")
                    put("result", result)
                },
            )
        }
    }
}
