package com.surrealdb.kotlin.query.api.query

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
    val first = statementArray(response).firstOrNull() as? JsonObject ?: return JsonNull
    return unwrapStatement(first)
}

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

internal fun resultRows(
    json: Json,
    result: JsonElement,
): List<Row> =
    resultRecords(result).map { record ->
        Row.fromJson(
            json,
            record as? JsonObject
                ?: throw SurrealProtocolException(
                    "Expected a record in the result, got $record. Read a value that is not a record with decodeAs().",
                ),
        )
    }

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
