package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.error.SurrealFeatureNotSupportedException
import com.surrealdb.kotlin.api.live.LiveMode
import com.surrealdb.kotlin.api.live.LiveQueryEvent
import com.surrealdb.kotlin.api.live.LiveQuerySubscription
import com.surrealdb.kotlin.api.live.liveEventFlow
import com.surrealdb.kotlin.api.live.toLiveStatement
import com.surrealdb.kotlin.api.query.BoundQuery
import com.surrealdb.kotlin.api.query.QueryContext
import com.surrealdb.kotlin.api.query.firstQueryResult
import com.surrealdb.kotlin.api.query.surql
import com.surrealdb.kotlin.runtime.ConnectionController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * A namespace, database, auth token and set of variables on a [Surreal]
 * connection.
 *
 * Obtained from [Surreal.session]. Sessions on one client share the transport
 * and nothing else, so signing in on one leaves the others untouched.
 */
public class Session internal constructor(
    internal val controller: ConnectionController,
    internal val sessionId: String,
) : QueryContext {
    private val authMutex = Mutex()

    /** Current namespace for this session, or null if none has been selected. */
    public suspend fun namespace(): String? = controller.snapshot(sessionId).namespace

    /** Current database for this session, or null if none has been selected. */
    public suspend fun database(): String? = controller.snapshot(sessionId).database

    /** Current access token for this session, or null if not authenticated. */
    public suspend fun accessToken(): String? = controller.snapshot(sessionId).token

    public suspend fun ping(): JsonElement = withAutoAuthRetry { controller.health(sessionId) }

    public suspend fun version(): JsonElement = withAutoAuthRetry { controller.version(sessionId) }

    /** A name that is not there is not an error. See [Namespace]. */
    public suspend fun use(
        namespace: Namespace,
        database: Database,
    ): JsonElement {
        val result = withAutoAuthRetry { controller.use(sessionId, namespace.value, database.value) }
        controller.update(sessionId) {
            this.namespace = namespace.value
            this.database = database.value
        }
        return result
    }

    public suspend fun whoami(): JsonElement =
        try {
            firstQueryResult(query(BoundQuery().appendLiteral("SELECT * FROM ONLY \$auth")))
        } catch (cause: com.surrealdb.kotlin.api.error.SurrealRpcException) {
            // v3.0.5 emits this when `$auth` resolves to zero rows — the
            // semantically-correct return is "no auth record bound".
            if (cause.message?.contains("single result output", ignoreCase = true) == true) {
                kotlinx.serialization.json.JsonNull
            } else {
                throw cause
            }
        }

    /** Register a record through its access method and hold the token it returns. */
    public suspend fun signup(credentials: Credentials.ForSignUp): JsonElement {
        val result = withAutoAuthRetry { controller.signup(sessionId, credentials.toParams()) }
        applyTokenResult(result)
        return result
    }

    /**
     * Authenticate this session and hold the token it returns.
     *
     * A password containing something the parser reads as a record id, such as
     * `note: remember the milk`, cannot be used at all. The RPC turns a bound
     * string into a record before anything type-checks it, so the call fails
     * with `Expected string, got record`.
     */
    public suspend fun signin(credentials: Credentials.ForSignIn): JsonElement {
        val result = withAutoAuthRetry { controller.signin(sessionId, credentials.toParams()) }
        applyTokenResult(result)
        return result
    }

    public suspend fun authenticate(token: String): JsonElement {
        val result = withAutoAuthRetry { controller.authenticate(sessionId, token) }
        controller.update(sessionId) { accessToken = token }
        scheduleRenewalIfPossible()
        return result
    }

    public suspend fun invalidate(): JsonElement {
        val result = withAutoAuthRetry { controller.invalidate(sessionId) }
        controller.update(sessionId) {
            accessToken = null
            refreshToken = null
            renewalJob?.cancel()
            renewalJob = null
        }
        return result
    }

    public suspend fun reset(): JsonElement {
        val result = withAutoAuthRetry { controller.reset(sessionId) }
        controller.update(sessionId) {
            accessToken = null
            refreshToken = null
            namespace = null
            database = null
            variables.clear()
            renewalJob?.cancel()
            renewalJob = null
        }
        return result
    }

    @PublishedApi
    internal suspend fun setVariable(
        key: String,
        value: JsonElement,
    ): JsonElement {
        val result = withAutoAuthRetry { controller.set(sessionId, key, value) }
        controller.update(sessionId) { variables[key] = value }
        return result
    }

    /**
     * Bind [value] to the session variable [key], so `$key` names it in every
     * statement this session sends until [unset] removes it or [reset] clears
     * them all.
     *
     * The value is encoded by its Kotlin type with this session's serializer,
     * so a variable is bound the way a field is assigned rather than assembled
     * as a [JsonElement].
     *
     * Written in backticks at the call site as well, because it is named for
     * the `let` RPC method and `let` is a soft keyword in Kotlin.
     */
    @Suppress("ktlint:standard:function-naming")
    public suspend inline fun <reified V> `let`(
        key: String,
        value: V,
    ): JsonElement = setVariable(key, json.encodeToJsonElement(serializer<V>(), value))

    public suspend fun unset(key: String): JsonElement {
        val result = withAutoAuthRetry { controller.unset(sessionId, key) }
        controller.update(sessionId) { variables.remove(key) }
        return result
    }

    /** Dispatch a pre-built [BoundQuery] via the `query` RPC. */
    override suspend fun query(bound: BoundQuery): JsonElement =
        withAutoAuthRetry {
            controller.query(sessionId, bound.surql, bound.bindingsAsJsonObject().takeIf { it.isNotEmpty() })
        }

    /**
     * Subscribe to live notifications for changes on a table.
     *
     * To watch one record, or to filter, write the `LIVE SELECT` yourself and
     * run it via [query], which returns a live query UUID.
     */
    public suspend fun live(
        table: Table,
        mode: LiveMode = LiveMode.Records,
    ): LiveQuerySubscription = withAutoAuthRetry { controller.live(sessionId, table.tableName, mode.wantsDiff) }

    public fun <T> liveEvents(
        spec: String,
        decode: (JsonElement) -> T,
    ): Flow<LiveQueryEvent<T>> {
        if (Feature.LiveQueries !in controller.features) {
            throw SurrealFeatureNotSupportedException(
                "Live queries need a ws:// or wss:// connection; this client is on ${controller.config.url}",
            )
        }

        val statement = toLiveStatement(spec)

        return liveEventFlow(
            notifications = controller.liveNotifications,
            failures = controller.liveFailures,
            start = {
                val id = firstQueryResult(query(BoundQuery().appendLiteral(statement))).jsonPrimitive.content
                controller.trackLive(sessionId, id, statement)
                id
            },
            stop = { id -> kill(id) },
            decode = decode,
        )
    }

    /** As [liveEvents], decoding each record with this session's serializer. */
    public inline fun <reified T> liveEvents(spec: String): Flow<LiveQueryEvent<T>> = liveEvents(spec) { decode(it) }

    public suspend fun kill(liveQueryId: String): JsonElement =
        withAutoAuthRetry { controller.kill(sessionId, liveQueryId) }

    override val json: kotlinx.serialization.json.Json get() = controller.config.json

    public inline fun <reified T> decode(element: JsonElement): T = json.decodeFromJsonElement(element)

    private suspend fun applyTokenResult(result: JsonElement) {
        val tokens = extractTokens(result) ?: return
        controller.update(sessionId) {
            accessToken = tokens.access
            tokens.refresh?.let { refreshToken = it }
        }
        scheduleRenewalIfPossible()
    }

    private suspend fun scheduleRenewalIfPossible() {
        controller.scheduleRenewal(sessionId) {
            authMutex.lock()
            try {
                renewToken()
            } finally {
                authMutex.unlock()
            }
        }
    }

    private suspend fun renewToken() {
        var refresh: String? = null
        controller.update(sessionId) { refresh = refreshToken }
        val rt = refresh ?: return

        // SurrealDB v2 refresh-token RPC: { rt: <refresh_token> } via signin
        val params =
            kotlinx.serialization.json.buildJsonObject {
                put("rt", kotlinx.serialization.json.JsonPrimitive(rt))
            }
        val result = runCatching { controller.signin(sessionId, params) }.getOrNull() ?: return
        val tokens = extractTokens(result) ?: return
        controller.update(sessionId) {
            accessToken = tokens.access
            tokens.refresh?.let { refreshToken = it }
        }
        scheduleRenewalIfPossible()
    }

    private data class TokenPair(
        val access: String,
        val refresh: String?,
    )

    private fun extractTokens(result: JsonElement): TokenPair? =
        when {
            result is kotlinx.serialization.json.JsonPrimitive && result.isString -> {
                TokenPair(result.content, null)
            }

            result is JsonObject -> {
                val access = (result["access"] ?: result["token"] ?: result["jwt"])?.jsonPrimitive?.content
                val refresh = result["refresh"]?.jsonPrimitive?.content
                access?.let { TokenPair(it, refresh) }
            }

            else -> {
                null
            }
        }

    private suspend fun <T> withAutoAuthRetry(
        allowRetry: Boolean = true,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cause: com.surrealdb.kotlin.api.error.SurrealAuthenticationException) {
            if (!allowRetry || !controller.config.autoAuthenticate) throw cause
            val provider = controller.config.credentialProvider ?: throw cause
            val credential = provider() ?: throw cause
            applyAuthInput(credential)
            withAutoAuthRetry(allowRetry = false, block = block)
        }

    private suspend fun applyAuthInput(authInput: Credentials) {
        when (authInput) {
            is Credentials.ForSignIn -> {
                val result = controller.signin(sessionId, authInput.toParams())
                applyTokenResult(result)
            }

            is Credentials.Token -> {
                controller.authenticate(sessionId, authInput.token)
                controller.update(sessionId) { accessToken = authInput.token }
                scheduleRenewalIfPossible()
            }
        }
    }
}

private val LiveMode.wantsDiff: Boolean
    get() =
        when (this) {
            LiveMode.Records -> false
            LiveMode.Diffs -> true
        }
