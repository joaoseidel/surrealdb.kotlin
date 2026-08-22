package com.surrealdb.kotlin.core.api.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class LiveNotification(
    val action: String,
    @SerialName("id") val liveQueryId: String,
    val result: JsonElement,
    val record: String? = null,
)
