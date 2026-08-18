package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.live.SurrealLiveNotification
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fans every live-query notification a connection receives out to two places: a
 * per-subscription channel, and a broadcast carrying the lot.
 *
 * The broadcast exists because the per-subscription channel cannot be registered
 * early enough. A subscription is keyed by the id the server returns from the
 * `LIVE SELECT`, but the server starts publishing the moment that statement is
 * *executed*, so a notification produced between execution and registration has
 * nowhere to go and is dropped. Anything that subscribes to [notifications]
 * before sending the statement and filters by id afterward cannot lose that
 * window.
 */
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

    /** Every notification received on this connection, whatever query produced it. */
    val notifications: SharedFlow<SurrealLiveNotification> = _notifications.asSharedFlow()

    suspend fun register(liveQueryId: String): Flow<SurrealLiveNotification> {
        val channel = Channel<SurrealLiveNotification>(capacity = Channel.BUFFERED)
        mutex.withLock { channels.put(liveQueryId, channel) }?.close()
        return channel.receiveAsFlow()
    }

    suspend fun unregister(liveQueryId: String) {
        mutex.withLock { channels.remove(liveQueryId) }?.close()
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
                all
            }

        open.forEach { it.close(cause) }
    }
}
