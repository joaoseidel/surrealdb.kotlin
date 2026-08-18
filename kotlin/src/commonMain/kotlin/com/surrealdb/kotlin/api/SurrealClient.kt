package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.api.SurrealConnectionEvent
import com.surrealdb.kotlin.api.SurrealFeature
import com.surrealdb.kotlin.runtime.ConnectionController
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

public class SurrealClient private constructor(
    public val config: SurrealClientConfig,
    rootController: ConnectionController,
) : SurrealSession(rootController, rootController.rootSessionId), AutoCloseable {

    public constructor(config: SurrealClientConfig) : this(config, ConnectionController(config))

    /** Stream of connection lifecycle events from the underlying engine. */
    public val connectionEvents: SharedFlow<SurrealConnectionEvent>
        get() = controller.events

    /**
     * The live queries running on this connection: started and not yet killed.
     *
     * A subscription nobody collects any more still appears here — that is what
     * makes a leaked live query visible, since it otherwise goes on costing the
     * server work silently. Kill an id with [kill].
     *
     * The set empties when the client is closed. A dropped-and-reconnected socket
     * does *not* empty it: the server forgets its live queries with the session and
     * this client does not re-issue them, so after a reconnect the ids here name
     * queries that are no longer running.
     */
    public val activeLiveQueries: StateFlow<Set<String>>
        get() = controller.activeLiveQueries

    /** Capabilities the active engine supports. */
    public val features: Set<SurrealFeature>
        get() = controller.features

    /** Returns true if the active engine supports the given feature. */
    public fun supports(feature: SurrealFeature): Boolean = feature in features

    /**
     * Eagerly establish the connection. Calling this is only required when the
     * client was constructed with `autoConnect = false`.
     */
    public suspend fun connect() {
        controller.connect()
    }

    /**
     * Create a new session that shares the underlying connection but has its
     * own namespace, database, auth token and session variables.
     */
    public suspend fun newSession(): SurrealSession =
        SurrealSession(controller, controller.newSession())

    /** Remove a previously created session, cancelling any renewal jobs. */
    public suspend fun closeSession(session: SurrealSession) {
        if (session === this) return
        controller.removeSession(session.sessionId)
    }

    public override fun close() {
        controller.close()
    }
}
