package com.surrealdb.kotlin.api.live

import com.surrealdb.kotlin.api.data.RecordId
import kotlinx.serialization.json.JsonElement

public fun <T> LiveNotification.toEvent(decode: (JsonElement) -> T): LiveQueryEvent<T> {
    val recordId = record?.let(::parseRecordId)
    return when (action.uppercase()) {
        "CREATE" -> LiveQueryEvent.Created(liveQueryId, recordId, decode(result))
        "UPDATE" -> LiveQueryEvent.Updated(liveQueryId, recordId, decode(result))
        "DELETE" -> LiveQueryEvent.Deleted(liveQueryId, recordId, decode(result))
        else -> LiveQueryEvent.Other(liveQueryId, recordId, action)
    }
}

internal fun parseRecordId(raw: String): RecordId? {
    val separator = raw.indexOf(':')
    if (separator <= 0 || separator == raw.lastIndex) return null
    return RecordId(table = raw.substring(0, separator), id = raw.substring(separator + 1))
}
