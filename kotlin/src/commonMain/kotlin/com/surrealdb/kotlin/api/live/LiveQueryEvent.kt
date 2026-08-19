package com.surrealdb.kotlin.api.live

import com.surrealdb.kotlin.api.data.RecordId

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
     * [value] is the record's last known state.
     */
    public data class Deleted<out T>(
        override val queryId: String,
        override val record: RecordId?,
        public val value: T,
    ) : LiveQueryEvent<T>

    public data class Other<out T>(
        override val queryId: String,
        override val record: RecordId?,
        public val action: String,
    ) : LiveQueryEvent<T>
}
