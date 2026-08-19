package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.runtime.ConnectionController
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * A connection to SurrealDB.
 *
 * The client owns the transport and nothing else: it connects, reports what the
 * engine can do, and hands out sessions. Namespace, database, auth and session
 * variables all live on a [Session], obtained from [session] — the client is not
 * itself one, so there is no ambient context for a query to pick up by accident.
 */
public class Surreal private constructor(
    public val config: Config,
    private val controller: ConnectionController,
) : AutoCloseable {
    public constructor(config: Config) : this(config, ConnectionController(config))

    /** Stream of connection lifecycle events from the underlying engine. */
    public val connectionEvents: SharedFlow<ConnectionEvent>
        get() = controller.events

    /** The live queries running on this connection. */
    public val activeLiveQueries: StateFlow<Set<String>>
        get() = controller.activeLiveQueries

    /** Capabilities the active engine supports. */
    public val features: Set<Feature>
        get() = controller.features

    /** Returns true if the active engine supports the given feature. */
    public fun supports(feature: Feature): Boolean = feature in features

    /**
     * Eagerly establish the connection. Calling this is only required when the
     * client was constructed with `autoConnect = false`.
     */
    public suspend fun connect() {
        controller.connect()
    }

    /**
     * Open a session on this connection. Sessions share the transport and
     * nothing else — each has its own namespace, database, auth token and
     * variables.
     */
    public suspend fun session(): Session = Session(controller, controller.newSession())

    /** Discard a session, cancelling any token-renewal job it scheduled. */
    public suspend fun closeSession(session: Session) {
        controller.removeSession(session.sessionId)
    }

    public override fun close() {
        controller.close()
    }

    public data class Config(
        val url: String,
        val json: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = true
            },
        val autoAuthenticate: Boolean = false,
        val credentialProvider: (suspend () -> Credentials?)? = null,
        val httpClientFactory: ((Config) -> HttpClient)? = null,
        val requestTimeoutMillis: Long = 30_000,
        val reconnect: ReconnectConfig = ReconnectConfig(),
        /**
         * Schedule automatic JWT renewal this many milliseconds before the access
         * token's `exp` claim. Ignored when no refresh token is available.
         */
        val tokenRenewalLeadMillis: Long = 60_000,
        /**
         * Connect to the engine eagerly during `Surreal` construction.
         * Default is true to mirror surrealdb.js behavior.
         */
        val autoConnect: Boolean = true,
    )
}
