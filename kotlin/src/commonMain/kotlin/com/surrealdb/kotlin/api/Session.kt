package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.api.error.SurrealFeatureNotSupportedException
import com.surrealdb.kotlin.api.live.LiveQueryEvent
import com.surrealdb.kotlin.api.live.LiveQuerySubscription
import com.surrealdb.kotlin.api.live.liveEventFlow
import com.surrealdb.kotlin.api.live.toLiveStatement
import com.surrealdb.kotlin.api.query.BoundQuery
import com.surrealdb.kotlin.api.query.QueryContext
import com.surrealdb.kotlin.api.query.firstQueryResult
import com.surrealdb.kotlin.runtime.ConnectionController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public open class Session internal constructor(
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

    public suspend fun use(
        namespace: String,
        database: String,
    ): JsonElement {
        val result = withAutoAuthRetry { controller.use(sessionId, namespace, database) }
        controller.update(sessionId) {
            this.namespace = namespace
            this.database = database
        }
        return result
    }

    public suspend fun auth(): JsonElement =
        try {
            firstQueryResult(query(BoundQuery("SELECT * FROM ONLY \$auth")))
        } catch (cause: com.surrealdb.kotlin.api.error.SurrealRpcException) {
            // v3.0.5 emits this when `$auth` resolves to zero rows — the
            // semantically-correct return is "no auth record bound".
            if (cause.message?.contains("single result output", ignoreCase = true) == true) {
                kotlinx.serialization.json.JsonNull
            } else {
                throw cause
            }
        }

    public suspend fun signup(params: JsonObject): JsonElement {
        val result = withAutoAuthRetry { controller.signup(sessionId, params) }
        applyTokenResult(result)
        return result
    }

    public suspend fun signin(params: JsonObject): JsonElement {
        val result = withAutoAuthRetry { controller.signin(sessionId, params) }
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

    // Backticked because it is named for the SurrealDB RPC method it calls, and
    // `let` is a soft keyword here. Renaming it would break every caller, so the
    // naming rule is suppressed at this one declaration rather than repo-wide.
    @Suppress("ktlint:standard:function-naming")
    public suspend fun `let`(
        key: String,
        value: JsonElement,
    ): JsonElement {
        val result = withAutoAuthRetry { controller.set(sessionId, key, value) }
        controller.update(sessionId) { variables[key] = value }
        return result
    }

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
     * Subscribe to live notifications for changes on a table. The argument is a
     * table name or record id — to use complex `LIVE SELECT` SurrealQL, run it
     * via [query] which will return a live query UUID.
     */
    public suspend fun live(
        table: String,
        diff: Boolean? = null,
    ): LiveQuerySubscription = withAutoAuthRetry { controller.live(sessionId, table, diff) }

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
                val id = firstQueryResult(query(statement)).jsonPrimitive.content
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

    public suspend inline fun <reified T> queryAs(
        sql: String,
        vars: JsonObject? = null,
    ): T = decode(query(sql, vars))

    public suspend inline fun <reified T> queryAs(bound: BoundQuery): T = decode(query(bound))

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
            is Credentials.SignIn -> {
                val result = controller.signin(sessionId, authInput.params)
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
