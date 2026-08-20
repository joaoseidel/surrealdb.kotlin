package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.error.SurrealProtocolException
import com.surrealdb.kotlin.api.error.SurrealRpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extract the inner `result` of the first statement from a `query` RPC
 * response. SurrealDB returns `[{ status, time, result, type }, ...]` with one
 * entry per statement; the builder layer always sends a single statement so we
 * take the first entry and unwrap its `result`.
 *
 * Throws [SurrealRpcException] if the statement reports `status: "ERR"`.
 */
internal fun firstQueryResult(response: JsonElement): JsonElement {
    val first = statementArray(response).firstOrNull() as? JsonObject ?: return JsonNull
    return unwrapStatement(first)
}

/**
 * Every statement's `result`, in the order they were sent, for the callers that
 * send more than one in a single round trip.
 *
 * Throws [SurrealRpcException] for the first statement reporting `status: "ERR"`.
 */
internal fun statementResults(response: JsonElement): List<JsonElement> =
    statementArray(response).map { entry ->
        unwrapStatement(
            entry as? JsonObject
                ?: throw SurrealProtocolException("Expected a statement object in query response, got: $entry"),
        )
    }

private fun statementArray(response: JsonElement): JsonArray =
    response as? JsonArray
        ?: throw SurrealProtocolException("Expected array response from query, got: $response")

private fun unwrapStatement(statement: JsonObject): JsonElement {
    if (statement["status"]?.jsonPrimitive?.content == "ERR") {
        val message = statement["result"]?.jsonPrimitive?.content ?: "query failed"
        throw SurrealRpcException(code = null, message = message, data = statement)
    }
    return statement["result"] ?: JsonNull
}
