package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.error.SurrealProtocolException
import com.surrealdb.kotlin.api.error.SurrealRpcException
import kotlinx.serialization.json.Json
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

/**
 * The records of one statement's result, which SurrealDB shapes differently
 * depending on what the statement pointed at: an array for a table target, the
 * record itself under `ONLY` or a record-id target, and null for a statement
 * that matched nothing.
 *
 * A value that is neither, which is what `RETURN` and a `VALUE` projection
 * answer with, is one record too, so a scalar reads back as a scalar.
 */
internal fun resultRecords(result: JsonElement): List<JsonElement> =
    when (result) {
        is JsonArray -> result
        JsonNull -> emptyList()
        else -> listOf(result)
    }

/**
 * Every record of one statement's result as a [Row].
 *
 * A result that is not a record, which is what `RETURN` and a `VALUE`
 * projection answer with, has no fields to name and is read with
 * [Query.decodeAs] instead.
 */
internal fun resultRows(
    json: Json,
    result: JsonElement,
): List<Row> =
    resultRecords(result).map { record ->
        Row(
            json,
            record as? JsonObject
                ?: throw SurrealProtocolException(
                    "Expected a record in the result, got $record. Read a value that is not a record with decodeAs().",
                ),
        )
    }

/**
 * The one record a caller asked for, or null.
 *
 * More than one is a statement that asked the wrong question, and answering
 * with the first would hide it.
 */
internal fun <T> atMostOneRecord(records: List<T>): T? {
    if (records.size > 1) {
        throw SurrealProtocolException(
            "Expected at most one record, got ${records.size}. " +
                "Pair awaitSingleOrNull() with only() or limit(1).",
        )
    }
    return records.firstOrNull()
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
