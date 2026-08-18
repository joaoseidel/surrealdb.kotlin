package com.surrealdb.kotlin.api.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One live query notification, as it arrives on the wire.
 *
 * SurrealDB sends more than the documentation describes:
 *
 * ```json
 * {"action":"CREATE",
 *  "id":"1184a6bc-6968-462c-b2d6-8f6ac1ac5fd0",
 *  "record":"book:lq1",
 *  "result":{"id":"book:lq1","pages":1,"title":"Live One"},
 *  "session":"99ea5896-05af-4847-93da-7d1c4ccaa547"}
 * ```
 *
 * [record] is carried here because it identifies what the event concerns. `session` is a property
 * of the connection rather than of the event, so it is not modelled.
 *
 * Prefer [LiveQueryEvent], which types [action] and decodes [result]; this is the raw form.
 */
@Serializable
public data class SurrealLiveNotification(
    val action: String,
    @SerialName("id") val liveQueryId: String,
    val result: JsonElement,
    /** The record the event concerns, e.g. `book:lq1`. Absent on older servers. */
    val record: String? = null,
)
