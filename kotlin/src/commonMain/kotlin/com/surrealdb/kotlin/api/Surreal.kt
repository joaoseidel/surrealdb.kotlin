package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.runtime.ConnectionController
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

public class Surreal private constructor(
    public val config: Config,
    rootController: ConnectionController,
) : Session(rootController, rootController.rootSessionId),
    AutoCloseable {
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
     * Create a new session that shares the underlying connection but has its
     * own namespace, database, auth token and session variables.
     */
    public suspend fun newSession(): Session = Session(controller, controller.newSession())

    /** Remove a previously created session, cancelling any renewal jobs. */
    public suspend fun closeSession(session: Session) {
        if (session === this) return
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
