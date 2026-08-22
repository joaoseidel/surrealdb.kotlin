package com.surrealdb.kotlin.api.live

/**
 * What the server sends on each notification of a live subscription.
 *
 * ```
 * db.live(Books)                     // {"id": "book:sicp", "pages": 657}
 * db.live(Books, LiveMode.Diffs)     // [{"op": "replace", "path": "/pages", "value": 700}]
 * ```
 */
public enum class LiveMode {
    /** Each notification carries the record. */
    Records,

    /** Each notification carries a JSON-Patch diff (RFC 6902) of the change. */
    Diffs,
}
