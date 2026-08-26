package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.error.SurrealProtocolException

internal fun <T> atMostOneRecord(records: List<T>): T? {
    if (records.size > 1) {
        throw SurrealProtocolException(
            "Expected at most one record, got ${records.size}. " +
                "Pair awaitSingleOrNull() with only() or limit(1).",
        )
    }
    return records.firstOrNull()
}
