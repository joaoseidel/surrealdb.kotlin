package com.surrealdb.kotlin.core.api.live

import com.surrealdb.kotlin.core.api.data.RecordId
import kotlinx.serialization.json.JsonElement

public fun <T> LiveNotification.toEvent(decode: (JsonElement) -> T): LiveQueryEvent<T> {
    val recordId = record?.let(RecordId::parseOrNull)
    return when (action.uppercase()) {
        "CREATE" -> LiveQueryEvent.Created(liveQueryId, recordId, decode(result))
        "UPDATE" -> LiveQueryEvent.Updated(liveQueryId, recordId, decode(result))
        "DELETE" -> LiveQueryEvent.Deleted(liveQueryId, recordId, decode(result))
        else -> LiveQueryEvent.Other(liveQueryId, recordId, action)
    }
}
