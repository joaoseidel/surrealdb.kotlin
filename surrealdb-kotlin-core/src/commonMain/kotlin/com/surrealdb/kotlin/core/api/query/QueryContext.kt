package com.surrealdb.kotlin.core.api.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The context a query runs in: somewhere to send a [BoundQuery], and the
 * [Json] to decode what comes back.
 *
 * [com.surrealdb.kotlin.core.api.Session] sends on the session.
 * [com.surrealdb.kotlin.core.api.Transaction] sends the same query with its
 * transaction id at the RPC envelope level, so the server scopes it without an
 * explicit `BEGIN` round-trip.
 *
 * Sending is the whole contract, so sessions and transactions share the same
 * compiled-query path.
 */
public interface QueryContext {
    /** Serializer used to decode query results. */
    public val json: Json

    /** Send a compiled [BoundQuery] via the `query` RPC. */
    public suspend fun query(bound: BoundQuery): JsonElement
}
