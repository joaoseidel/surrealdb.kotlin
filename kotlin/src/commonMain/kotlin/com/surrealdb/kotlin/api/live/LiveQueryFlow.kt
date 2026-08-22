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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

private sealed interface LiveFlowSignal {
    data class Notification(
        val value: LiveNotification,
    ) : LiveFlowSignal

    data class Failure(
        val cause: Throwable,
    ) : LiveFlowSignal
}

internal fun <T> liveEventFlow(
    notifications: SharedFlow<LiveNotification>,
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
        var killed = false

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
                .map<LiveNotification, LiveFlowSignal> { LiveFlowSignal.Notification(it) }

        val broken =
            failures
                .filter { it.liveQueryId == queryId.await() }
                .map<LiveQueryFailure, LiveFlowSignal> { LiveFlowSignal.Failure(it.cause) }

        emitAll(
            merge(events, broken)
                .takeWhile { signal ->
                    val isKilled =
                        signal is LiveFlowSignal.Notification &&
                            signal.value.action.equals("KILLED", ignoreCase = true)
                    if (isKilled) killed = true
                    !isKilled
                }.map { signal ->
                    when (signal) {
                        is LiveFlowSignal.Notification -> signal.value.toEvent(decode)
                        is LiveFlowSignal.Failure -> throw signal.cause
                    }
                }.onCompletion { cause ->
                    val id = startedId ?: return@onCompletion
                    if (cause is SurrealLiveQueryException || killed) return@onCompletion
                    withContext(NonCancellable) { runCatching { stop(id) } }
                },
        )
    }
