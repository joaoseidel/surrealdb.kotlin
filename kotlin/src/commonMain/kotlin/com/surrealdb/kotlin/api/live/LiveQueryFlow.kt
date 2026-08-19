package com.surrealdb.kotlin.api.live

import com.surrealdb.kotlin.api.error.SurrealLiveQueryException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

internal fun <T> liveEventFlow(
    notifications: SharedFlow<SurrealLiveNotification>,
    failures: SharedFlow<LiveQueryFailure>,
    start: suspend () -> String,
    stop: suspend (String) -> Unit,
    decode: (JsonElement) -> T,
): Flow<LiveQueryEvent<T>> =
    flow {
        val queryId = CompletableDeferred<String>()

        // Confined to this collection, so it says what *this* collector started —
        // which is exactly what onCompletion may need to kill, and nothing else.
        var startedId: String? = null

        val events =
            notifications
                .onSubscription {
                    runCatching { start() }
                        .onSuccess {
                            startedId = it
                            queryId.complete(it)
                        }.onFailure {
                            queryId.completeExceptionally(it)
                            throw it
                        }
                }.filter { it.liveQueryId == queryId.await() }
                .map { it.toEvent(decode) }

        // A subscription that can no longer deliver reaches the collector as a
        // thrown exception. Nothing on the notification flow can say "this query is
        // over", so a collector filtering it would otherwise just stop seeing events.
        val broken =
            failures
                .filter { it.liveQueryId == queryId.await() }
                .map<LiveQueryFailure, LiveQueryEvent<T>> { throw it.cause }

        emitAll(
            merge(events, broken).onCompletion { cause ->
                val id = startedId ?: return@onCompletion
                if (cause is SurrealLiveQueryException) return@onCompletion
                withContext(NonCancellable) { runCatching { stop(id) } }
            },
        )
    }
