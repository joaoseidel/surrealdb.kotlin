package com.surrealdb.kotlin.core.api.query

import com.surrealdb.kotlin.core.api.data.Row
import kotlinx.serialization.json.Json

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

    /**
     * Send a compiled [BoundQuery] and read back everything every statement
     * answered with, in the order the statements were written. A statement that
     * failed throws rather than answering.
     *
     * A `VALUE` projection and `RETURN` answer with values that are not
     * records, so a [Row] here reads only through [Row.decode].
     */
    public suspend fun queryValues(bound: BoundQuery): List<Row>

    /** As [queryValues], refusing a result that is not a record. */
    public suspend fun query(bound: BoundQuery): List<Row> = queryValues(bound).onEach { it.requireRecord() }
}
