package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.live.SurrealLiveNotification
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LiveNotificationRouter {
    private val mutex = Mutex()
    private val channels = mutableMapOf<String, Channel<SurrealLiveNotification>>()

    // DROP_OLDEST, never SUSPEND: this is emitted into from the socket read loop,
    // which also carries every RPC response, so a collector that stops reading must
    // not be able to stall it — that would hang every in-flight call, not just its
    // own subscription. The buffer absorbs a burst; past it the slowest collector
    // loses the oldest notification and the rest are unaffected.
    private val _notifications =
        MutableSharedFlow<SurrealLiveNotification>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val _activeQueries = MutableStateFlow<Set<String>>(emptySet())

    val notifications: SharedFlow<SurrealLiveNotification> = _notifications.asSharedFlow()

    val activeQueries: StateFlow<Set<String>> = _activeQueries.asStateFlow()

    suspend fun track(liveQueryId: String) {
        mutex.withLock { _activeQueries.value += liveQueryId }
    }

    suspend fun register(liveQueryId: String): Flow<SurrealLiveNotification> {
        val channel = Channel<SurrealLiveNotification>(capacity = Channel.BUFFERED)
        mutex
            .withLock {
                _activeQueries.value += liveQueryId
                channels.put(liveQueryId, channel)
            }?.close()
        return channel.receiveAsFlow()
    }

    suspend fun untrack(liveQueryId: String) {
        mutex
            .withLock {
                _activeQueries.value -= liveQueryId
                channels.remove(liveQueryId)
            }?.close()
    }

    suspend fun route(notification: SurrealLiveNotification) {
        _notifications.tryEmit(notification)
        mutex.withLock { channels[notification.liveQueryId] }?.trySend(notification)
    }

    suspend fun closeAll(cause: Throwable? = null) {
        val open =
            mutex.withLock {
                val all = channels.values.toList()
                channels.clear()
                _activeQueries.value = emptySet()
                all
            }

        open.forEach { it.close(cause) }
    }
}
