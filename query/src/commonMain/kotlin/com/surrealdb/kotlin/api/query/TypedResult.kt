package com.surrealdb.kotlin.api.query

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * A statement together with the type its records decode into, from
 * [Query.decodeAs]. It is a holder rather than another builder: nothing is sent
 * until [await] or [awaitSingleOrNull], and no clause can be added after the
 * type is named.
 */
public class TypedResult<T>
    @PublishedApi
    internal constructor(
        private val query: Query,
        private val serializer: KSerializer<T>,
    ) {
        /**
         * Send the statement and decode every record it answered with. A
         * statement that matched nothing answers with an empty list.
         */
        public suspend fun await(): List<T> = resultRecords(query.result()).map(::decode)

        /**
         * Send the statement and decode the one record it answered with, or
         * null if it matched nothing.
         *
         * Two records is a query that asked the wrong question, so this throws
         * rather than picking one. Pair it with `only()` or `limit(1)`.
         */
        public suspend fun awaitSingleOrNull(): T? = atMostOneRecord(resultRecords(query.result()))?.let(::decode)

        private fun decode(record: JsonElement): T = query.context.json.decodeFromJsonElement(serializer, record)
    }
