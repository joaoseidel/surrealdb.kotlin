package com.surrealdb.kotlin.runtime.codec

import com.surrealdb.kotlin.api.live.LiveNotification
import com.surrealdb.kotlin.runtime.RpcResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun parseLiveNotification(response: RpcResponse): LiveNotification? {
    val result = response.result as? JsonObject ?: return null
    val action = result["action"]?.jsonPrimitive?.content ?: return null
    val liveQueryId = result["id"]?.jsonPrimitive?.content ?: return null
    val payload = result["result"] ?: JsonNull
    val record = result["record"]?.jsonPrimitive?.contentOrNull
    return LiveNotification(
        action = action,
        liveQueryId = liveQueryId,
        result = payload,
        record = record,
    )
}
