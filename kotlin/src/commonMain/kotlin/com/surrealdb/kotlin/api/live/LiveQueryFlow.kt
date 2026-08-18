package com.surrealdb.kotlin.api.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

internal fun <T> liveEventFlow(
    notifications: SharedFlow<SurrealLiveNotification>,
    start: suspend () -> String,
    stop: suspend (String) -> Unit,
    decode: (JsonElement) -> T,
): Flow<LiveQueryEvent<T>> =
    flow {
        val queryId = CompletableDeferred<String>()

        // Confined to this collection, so it says what *this* collector started —
        // which is exactly what onCompletion may need to kill, and nothing else.
        var startedId: String? = null

        emitAll(
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
                .onCompletion {
                    val id = startedId ?: return@onCompletion
                    withContext(NonCancellable) { runCatching { stop(id) } }
                },
        )
    }
