package com.surrealdb.kotlin.core.api.query

import com.surrealdb.kotlin.core.api.data.Row
import com.surrealdb.kotlin.core.api.error.SurrealProtocolException
import com.surrealdb.kotlin.core.api.error.SurrealRpcException
import kotlinx.serialization.json.Json
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
    return unwrapStatement(first)
}

internal fun statementResults(response: JsonElement): List<JsonElement> {
    val statements =
        response as? JsonArray
            ?: throw SurrealProtocolException("Expected array response from query, got: $response")
    return statements.map { entry ->
        unwrapStatement(
            entry as? JsonObject
                ?: throw SurrealProtocolException("Expected a statement object in query response, got: $entry"),
        )
    }
}

/** See [Row.fromResult], which is this shaped into rows. */
internal fun resultRecords(result: JsonElement): List<JsonElement> =
    when (result) {
        is JsonArray -> result
        JsonNull -> emptyList()
        else -> listOf(result)
    }

internal fun queryRows(
    json: Json,
    response: JsonElement,
): List<Row> = statementResults(response).flatMap { Row.fromResult(json, it) }

private fun unwrapStatement(statement: JsonObject): JsonElement {
    if (statement["status"]?.jsonPrimitive?.content == "ERR") {
        val message = statement["result"]?.jsonPrimitive?.content ?: "query failed"
        throw SurrealRpcException(code = null, message = message, data = statement)
    }
    return statement["result"] ?: JsonNull
}
