package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.live.LiveQueryFailure
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
    private val entries = mutableMapOf<String, Entry>()
    private val serverIds = mutableMapOf<String, String>()

    private class Entry(
        val id: String,
        val channel: Channel<SurrealLiveNotification>?,
        val source: LiveQuerySource?,
        var serverId: String,
    )

    internal class TrackedLiveQuery(
        val id: String,
        val source: LiveQuerySource,
    )

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

    private val _failures =
        MutableSharedFlow<LiveQueryFailure>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val _activeQueries = MutableStateFlow<Set<String>>(emptySet())

    val notifications: SharedFlow<SurrealLiveNotification> = _notifications.asSharedFlow()

    val failures: SharedFlow<LiveQueryFailure> = _failures.asSharedFlow()

    val activeQueries: StateFlow<Set<String>> = _activeQueries.asStateFlow()

    suspend fun track(
        liveQueryId: String,
        source: LiveQuerySource? = null,
    ) {
        mutex.withLock { put(Entry(liveQueryId, channel = null, source = source, serverId = liveQueryId)) }?.close()
    }

    suspend fun register(
        liveQueryId: String,
        source: LiveQuerySource? = null,
    ): Flow<SurrealLiveNotification> {
        val channel = Channel<SurrealLiveNotification>(capacity = Channel.BUFFERED)
        mutex.withLock { put(Entry(liveQueryId, channel, source, serverId = liveQueryId)) }?.close()
        return channel.receiveAsFlow()
    }

    suspend fun untrack(liveQueryId: String) {
        mutex.withLock { remove(liveQueryId) }?.channel?.close()
    }

    /** The server id a kill has to name, which is the most recent one it issued. */
    suspend fun serverIdFor(liveQueryId: String): String =
        mutex.withLock { entries[liveQueryId]?.serverId ?: liveQueryId }

    /** The live queries that can be started again, in the order they were tracked. */
    suspend fun reissuable(): List<TrackedLiveQuery> =
        mutex.withLock {
            entries.values.mapNotNull { entry -> entry.source?.let { TrackedLiveQuery(entry.id, it) } }
        }

    /** Point an existing subscription at the id its re-issued statement returned. */
    suspend fun rebind(
        liveQueryId: String,
        serverId: String,
    ) {
        mutex.withLock {
            val entry = entries[liveQueryId] ?: return@withLock
            serverIds.remove(entry.serverId)
            entry.serverId = serverId
            serverIds[serverId] = entry.id
        }
    }

    suspend fun fail(
        liveQueryId: String,
        cause: Throwable,
    ) {
        val removed = mutex.withLock { remove(liveQueryId) } ?: return
        removed.channel?.close(cause)
        _failures.tryEmit(LiveQueryFailure(removed.id, cause))
    }

    suspend fun route(notification: SurrealLiveNotification) {
        val entry = mutex.withLock { serverIds[notification.liveQueryId]?.let(entries::get) }
        val outgoing =
            when {
                entry == null || entry.id == notification.liveQueryId -> notification
                else -> notification.copy(liveQueryId = entry.id)
            }

        _notifications.tryEmit(outgoing)
        entry?.channel?.trySend(outgoing)
    }

    suspend fun closeAll(cause: Throwable? = null) {
        val open =
            mutex.withLock {
                val all = entries.values.toList()
                entries.clear()
                serverIds.clear()
                _activeQueries.value = emptySet()
                all
            }

        open.forEach { entry ->
            entry.channel?.close(cause)
            if (cause != null) _failures.tryEmit(LiveQueryFailure(entry.id, cause))
        }
    }

    private fun put(entry: Entry): Channel<SurrealLiveNotification>? {
        val previous = entries.put(entry.id, entry)
        previous?.let { serverIds.remove(it.serverId) }
        serverIds[entry.serverId] = entry.id
        _activeQueries.value = entries.keys.toSet()
        return previous?.channel
    }

    private fun remove(liveQueryId: String): Entry? {
        val id = liveQueryId.takeIf(entries::containsKey) ?: serverIds[liveQueryId] ?: return null
        val removed = entries.remove(id) ?: return null
        serverIds.remove(removed.serverId)
        _activeQueries.value = entries.keys.toSet()
        return removed
    }
}
