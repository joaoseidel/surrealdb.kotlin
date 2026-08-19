package com.surrealdb.kotlin.api.query

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A statement under construction. Each builder is an immutable value that
 * describes one statement and costs nothing until a terminal operation sends
 * it — [await], [awaitRaw] or [awaitAs].
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
    public suspend fun await(): JsonElement = firstQueryResult(context.query(compile()))

    /** Send the statement and return the raw `[{ status, result, time, type }]` envelope. */
    public suspend fun awaitRaw(): JsonElement = context.query(compile())
}

/** As [Query.await], decoding the result with the context's serializer. */
public suspend inline fun <reified T> Query.awaitAs(): T = context.json.decodeFromJsonElement(await())
