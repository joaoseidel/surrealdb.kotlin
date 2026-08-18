package com.surrealdb.kotlin.api.live

import com.surrealdb.kotlin.api.query.RecordId

/**
 * A typed live query notification.
 *
 * Exists so that callers branch on a type rather than on [SurrealLiveNotification.action], and
 * receive a decoded value rather than raw JSON.
 */
public sealed interface LiveQueryEvent<out T> {
    /** UUID of the live query that produced this event; the value [kill][com.surrealdb.kotlin.api.SurrealSession.kill] takes. */
    public val queryId: String

    /** The record this event concerns, or `null` if the server did not send one. */
    public val record: RecordId?

    public data class Created<out T>(
        override val queryId: String,
        override val record: RecordId?,
        public val value: T,
    ) : LiveQueryEvent<T>

    public data class Updated<out T>(
        override val queryId: String,
        override val record: RecordId?,
        public val value: T,
    ) : LiveQueryEvent<T>

    /**
     * A deletion.
     *
     * [value] is the record's last known state: SurrealDB sends the whole record on delete, not
     * merely its id, which is why this carries a value like the other cases.
     */
    public data class Deleted<out T>(
        override val queryId: String,
        override val record: RecordId?,
        public val value: T,
    ) : LiveQueryEvent<T>

    /**
     * An action this driver does not model.
     *
     * SurrealDB emits `KILLED` when a live query ends and may add actions in future versions.
     * Surfacing the unknown beats dropping it silently.
     */
    public data class Other<out T>(
        override val queryId: String,
        override val record: RecordId?,
        public val action: String,
    ) : LiveQueryEvent<T>
}
