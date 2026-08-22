package com.surrealdb.kotlin.core.api.live

/**
 * A live query that can no longer deliver. Carried on its own flow because the
 * broadcast of notifications has no way to express one; a collector filtering
 * it by id would otherwise just go quiet.
 */
internal data class LiveQueryFailure(
    val liveQueryId: String,
    val cause: Throwable,
)
