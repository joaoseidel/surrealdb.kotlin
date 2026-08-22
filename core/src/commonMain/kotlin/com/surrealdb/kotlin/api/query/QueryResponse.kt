package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.error.SurrealProtocolException
import com.surrealdb.kotlin.api.error.SurrealRpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun firstQueryResult(response: JsonElement): JsonElement {
    val statements =
        response as? JsonArray
            ?: throw SurrealProtocolException("Expected array response from query, got: $response")
    val first = statements.firstOrNull() as? JsonObject ?: return JsonNull
    if (first["status"]?.jsonPrimitive?.content == "ERR") {
        val message = first["result"]?.jsonPrimitive?.content ?: "query failed"
        throw SurrealRpcException(code = null, message = message, data = first)
    }
    return first["result"] ?: JsonNull
}
