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

    private val _activeQueries = MutableStateFlow<Set<String>>(emptySet())

    /** Every notification received on this connection, whatever query produced it. */
    val notifications: SharedFlow<SurrealLiveNotification> = _notifications.asSharedFlow()

    /**
     * The ids of the live queries running on this connection: started and not yet
     * killed.
     *
     * A subscription nobody is left collecting still appears here, which is the
     * point — a leaked live query is otherwise invisible, and goes on costing the
     * server work with nothing reading the result.
     *
     * The set is emptied when the connection ends. It is *not* emptied when the
     * socket drops and reconnects: the server forgets its live queries with the
     * session, but this engine does not yet re-issue them, so the ids here outlive
     * what they name until the client is closed.
     */
    val activeQueries: StateFlow<Set<String>> = _activeQueries.asStateFlow()

    /**
     * Records a live query as running. All a collector of [notifications] needs,
     * having no channel of its own to open.
     */
    suspend fun track(liveQueryId: String) {
        mutex.withLock { _activeQueries.value += liveQueryId }
    }

    /**
     * Tracks a live query and buffers its notifications into a channel of its own,
     * for a caller that wants a flow it can hand out.
     *
     * Registering an id twice closes the channel it replaces, so that collector
     * completes rather than waiting forever on notifications now going elsewhere.
     */
    suspend fun register(liveQueryId: String): Flow<SurrealLiveNotification> {
        val channel = Channel<SurrealLiveNotification>(capacity = Channel.BUFFERED)
        mutex
            .withLock {
                _activeQueries.value += liveQueryId
                channels.put(liveQueryId, channel)
            }?.close()
        return channel.receiveAsFlow()
    }

    /**
     * Records a live query as no longer running and closes any channel opened for
     * it. Untracking an id that was never tracked does nothing, so a caller that
     * kills the same subscription twice does not have to check. The broadcast is
     * unaffected.
     */
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
