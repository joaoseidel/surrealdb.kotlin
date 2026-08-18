package com.surrealdb.kotlin.api.live

import com.surrealdb.kotlin.api.query.RecordId
import kotlinx.serialization.json.JsonElement

/**
 * Turns a raw [SurrealLiveNotification] into a typed [LiveQueryEvent], decoding the payload with
 * [decode].
 *
 * Actions are matched case-insensitively; anything unrecognised becomes [LiveQueryEvent.Other]
 * rather than an error, so a newer server cannot break a running collector.
 */
public fun <T> SurrealLiveNotification.toEvent(decode: (JsonElement) -> T): LiveQueryEvent<T> {
    val recordId = record?.let(::parseRecordId)
    return when (action.uppercase()) {
        "CREATE" -> LiveQueryEvent.Created(liveQueryId, recordId, decode(result))
        "UPDATE" -> LiveQueryEvent.Updated(liveQueryId, recordId, decode(result))
        "DELETE" -> LiveQueryEvent.Deleted(liveQueryId, recordId, decode(result))
        else -> LiveQueryEvent.Other(liveQueryId, recordId, action)
    }
}

/**
 * Splits `table:id` on the first colon, because the id half may itself contain one
 * (`book:⟨a:b⟩`). A value with no colon is not a record id and yields `null`.
 */
internal fun parseRecordId(raw: String): RecordId? {
    val separator = raw.indexOf(':')
    if (separator <= 0 || separator == raw.lastIndex) return null
    return RecordId(table = raw.substring(0, separator), id = raw.substring(separator + 1))
}
