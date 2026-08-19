package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.live.LiveQueryFailure
import com.surrealdb.kotlin.api.live.SurrealLiveNotification
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext

internal const val HELD_NOTIFICATION_LIMIT = 64

internal class LiveNotificationRouter {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val serverIds = mutableMapOf<String, String>()

    private var registrationsInFlight = 0
    private val held = ArrayDeque<SurrealLiveNotification>()

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

    private class Dispatch(
        val notification: SurrealLiveNotification?,
        val channel: Channel<SurrealLiveNotification>?,
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

    /**
     * Run [block] — asking the server to start a live query and registering whatever
     * id it answers with — as one step, from the router's point of view.
     *
     * A server may push a notification before the client has read the reply naming
     * the subscription, and until that id is known nothing here can attribute it. For
     * the length of [block] such notifications are held rather than released to the
     * broadcast alone, and [register], [track] and [rebind] take the ones addressed to
     * the id they just learned. Whatever is left over is released unattributed when
     * the last registration finishes, so nothing is lost either way.
     *
     * The hold therefore lasts exactly one in-flight registration — bounded by the
     * request timeout, since that is what bounds [block] — and needs no clock of its
     * own. It is bounded in size too: past [HELD_NOTIFICATION_LIMIT] the oldest held
     * one is released unattributed, which is what would have happened to all of them.
     *
     * The window is closed under [NonCancellable]: re-issuing after a reconnect is a
     * cancellable job, and a lock taken on the way out of a cancelled coroutine is
     * never granted. Losing that decrement would hold every later notification for
     * the life of the connection.
     */
    suspend fun <T> attributing(block: suspend () -> T): T {
        mutex.withLock { registrationsInFlight++ }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                val released = mutex.withLock { if (--registrationsInFlight > 0) emptyList() else drainHeld() }
                released.forEach { _notifications.tryEmit(it) }
            }
        }
    }

    suspend fun track(
        liveQueryId: String,
        source: LiveQuerySource? = null,
    ) {
        val entry = Entry(liveQueryId, channel = null, source = source, serverId = liveQueryId)
        var previous: Channel<SurrealLiveNotification>? = null
        val claimed =
            mutex.withLock {
                previous = put(entry)
                claim(serverId = liveQueryId, stableId = liveQueryId)
            }

        previous?.close()
        claimed.forEach { _notifications.tryEmit(it) }
    }

    suspend fun register(
        liveQueryId: String,
        source: LiveQuerySource? = null,
    ): Flow<SurrealLiveNotification> {
        val channel = Channel<SurrealLiveNotification>(capacity = Channel.BUFFERED)
        var previous: Channel<SurrealLiveNotification>? = null
        val claimed =
            mutex.withLock {
                previous = put(Entry(liveQueryId, channel, source, serverId = liveQueryId))
                claim(serverId = liveQueryId, stableId = liveQueryId)
            }

        previous?.close()
        claimed.forEach {
            _notifications.tryEmit(it)
            channel.trySend(it)
        }
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
        var channel: Channel<SurrealLiveNotification>? = null
        val claimed =
            mutex.withLock {
                val entry = entries[liveQueryId] ?: return@withLock emptyList()
                serverIds.remove(entry.serverId)
                entry.serverId = serverId
                serverIds[serverId] = entry.id
                channel = entry.channel
                claim(serverId = serverId, stableId = entry.id)
            }

        claimed.forEach {
            _notifications.tryEmit(it)
            channel?.trySend(it)
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
        val dispatch =
            mutex.withLock {
                val entry = serverIds[notification.liveQueryId]?.let(entries::get)
                when {
                    entry != null -> Dispatch(relabelled(entry, notification), entry.channel)
                    registrationsInFlight == 0 -> Dispatch(notification, null)
                    else -> Dispatch(hold(notification), null)
                }
            }

        dispatch.notification?.let {
            _notifications.tryEmit(it)
            dispatch.channel?.trySend(it)
        }
    }

    suspend fun closeAll(cause: Throwable? = null) {
        var released: List<SurrealLiveNotification> = emptyList()
        val open =
            mutex.withLock {
                val all = entries.values.toList()
                entries.clear()
                serverIds.clear()
                released = drainHeld()
                _activeQueries.value = emptySet()
                all
            }

        released.forEach { _notifications.tryEmit(it) }
        open.forEach { entry ->
            entry.channel?.close(cause)
            if (cause != null) _failures.tryEmit(LiveQueryFailure(entry.id, cause))
        }
    }

    private fun relabelled(
        entry: Entry,
        notification: SurrealLiveNotification,
    ) = when (entry.id) {
        notification.liveQueryId -> notification
        else -> notification.copy(liveQueryId = entry.id)
    }

    private fun hold(notification: SurrealLiveNotification): SurrealLiveNotification? {
        held.addLast(notification)
        return if (held.size > HELD_NOTIFICATION_LIMIT) held.removeFirst() else null
    }

    private fun claim(
        serverId: String,
        stableId: String,
    ): List<SurrealLiveNotification> {
        if (held.isEmpty()) return emptyList()
        val mine = held.filter { it.liveQueryId == serverId }
        if (mine.isEmpty()) return emptyList()

        held.removeAll { it.liveQueryId == serverId }
        return if (serverId == stableId) mine else mine.map { it.copy(liveQueryId = stableId) }
    }

    private fun drainHeld(): List<SurrealLiveNotification> {
        if (held.isEmpty()) return emptyList()
        val all = held.toList()
        held.clear()
        return all
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
