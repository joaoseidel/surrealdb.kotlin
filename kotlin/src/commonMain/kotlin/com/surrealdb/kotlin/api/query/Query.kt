package com.surrealdb.kotlin.api.query

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * A statement under construction. Each builder is an immutable value that
 * describes one statement and costs nothing until a terminal operation sends
 * it.
 *
 * Chain methods return a fresh instance, so a builder is safe to share or pin
 * to a variable. It dispatches on the [QueryContext] it was built from, which
 * is what scopes a builder made inside a transaction to that transaction.
 */
public abstract class Query internal constructor(
    @PublishedApi internal val context: QueryContext,
) {
    /** Compile to SurrealQL and its bindings, without sending anything. */
    public abstract fun compile(): BoundQuery

    /** Send the statement and return the unwrapped first-statement result. */
    public suspend fun await(): JsonElement = result()

    /**
     * Decode what this statement answers with as [T], where [T] is the type of
     * one record: a statement answering with three records decodes as three
     * [T]s, not as one `List<T>`.
     *
     * It holds the decoding rather than performing it, so the terminals stay in
     * one place and the type is named where a reader expects it, last:
     *
     * ```
     * db.select(Users).decodeAs<User>().await()                 // List<User>
     * db.select(Users["alice"]).decodeAs<User>().awaitSingleOrNull()   // User?
     * ```
     */
    public inline fun <reified T> decodeAs(): TypedResult<T> = TypedResult(this, serializer())

    internal suspend fun result(): JsonElement = firstQueryResult(context.query(compile()))
}
