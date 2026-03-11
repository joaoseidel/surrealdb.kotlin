package com.surrealdb.kotlin

import com.surrealdb.kotlin.error.SurrealAuthenticationException
import com.surrealdb.kotlin.error.SurrealRpcException
import com.surrealdb.kotlin.error.SurrealTransportException
import com.surrealdb.kotlin.internal.SurrealCodec
import com.surrealdb.kotlin.internal.WsDispatcher
import com.surrealdb.kotlin.internal.normalizeRpcEndpoint
import com.surrealdb.kotlin.internal.randomRequestId
import com.surrealdb.kotlin.live.LiveQuerySubscription
import com.surrealdb.kotlin.model.SurrealRpcError
import com.surrealdb.kotlin.model.SurrealRpcRequest
import com.surrealdb.kotlin.model.SurrealRpcResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

public class SurrealClient(
    public val config: SurrealClientConfig,
) : AutoCloseable {
    private val ownsHttpClient = config.httpClientFactory == null
    private val httpClient: HttpClient = config.httpClientFactory?.invoke(config) ?: defaultHttpClient(config)
    private val codec = SurrealCodec(config)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val stateMutex = Mutex()
    private var authToken: String? = null
    private var namespace: String? = null
    private var database: String? = null

    private val wsDispatcher = WsDispatcher(
        client = httpClient,
        config = config,
        codec = codec,
        scope = scope,
    )

    public suspend fun rpc(
        method: String,
        params: List<JsonElement> = emptyList(),
    ): JsonElement = withAutoAuthRetry {
        executeHttpRpc(method, params)
    }

    public suspend fun rpcResult(
        method: String,
        params: List<JsonElement> = emptyList(),
    ): Result<JsonElement> = runCatching { rpc(method, params) }

    public suspend fun ping(): JsonElement = rpc("ping")
    public suspend fun pingResult(): Result<JsonElement> = runCatching { ping() }

    public suspend fun version(): JsonElement = rpc("version")
    public suspend fun versionResult(): Result<JsonElement> = runCatching { version() }

    public suspend fun use(namespace: String, database: String): JsonElement {
        val result = rpc("use", listOf(JsonPrimitive(namespace), JsonPrimitive(database)))
        stateMutex.withLock {
            this.namespace = namespace
            this.database = database
        }
        return result
    }

    public suspend fun useResult(namespace: String, database: String): Result<JsonElement> = runCatching {
        use(namespace, database)
    }

    public suspend fun info(): JsonElement = rpc("info")
    public suspend fun infoResult(): Result<JsonElement> = runCatching { info() }

    public suspend fun signup(params: JsonObject): JsonElement {
        val result = rpc("signup", listOf(params))
        updateTokenFromResult(result)
        return result
    }

    public suspend fun signupResult(params: JsonObject): Result<JsonElement> = runCatching { signup(params) }

    public suspend fun signin(params: JsonObject): JsonElement {
        val result = rpc("signin", listOf(params))
        updateTokenFromResult(result)
        return result
    }

    public suspend fun signinResult(params: JsonObject): Result<JsonElement> = runCatching { signin(params) }

    public suspend fun authenticate(token: String): JsonElement {
        val result = rpc("authenticate", listOf(JsonPrimitive(token)))
        stateMutex.withLock {
            authToken = token
        }
        return result
    }

    public suspend fun authenticateResult(token: String): Result<JsonElement> = runCatching { authenticate(token) }

    public suspend fun invalidate(): JsonElement {
        val result = rpc("invalidate")
        stateMutex.withLock {
            authToken = null
        }
        return result
    }

    public suspend fun invalidateResult(): Result<JsonElement> = runCatching { invalidate() }

    public suspend fun `let`(key: String, value: JsonElement): JsonElement =
        rpc("let", listOf(JsonPrimitive(key), value))

    public suspend fun letResult(key: String, value: JsonElement): Result<JsonElement> = runCatching {
        `let`(key, value)
    }

    public suspend fun unset(key: String): JsonElement = rpc("unset", listOf(JsonPrimitive(key)))
    public suspend fun unsetResult(key: String): Result<JsonElement> = runCatching { unset(key) }

    public suspend fun query(sql: String, vars: JsonObject? = null): JsonElement {
        val params = buildList {
            add(JsonPrimitive(sql))
            if (vars != null) {
                add(vars)
            }
        }
        return rpc("query", params)
    }

    public suspend fun queryResult(sql: String, vars: JsonObject? = null): Result<JsonElement> = runCatching {
        query(sql, vars)
    }

    public suspend fun select(thing: String): JsonElement = rpc("select", listOf(JsonPrimitive(thing)))
    public suspend fun selectResult(thing: String): Result<JsonElement> = runCatching { select(thing) }

    public suspend fun create(thing: String, data: JsonElement? = null): JsonElement {
        val params = buildList {
            add(JsonPrimitive(thing))
            if (data != null) {
                add(data)
            }
        }
        return rpc("create", params)
    }

    public suspend fun createResult(thing: String, data: JsonElement? = null): Result<JsonElement> = runCatching {
        create(thing, data)
    }

    public suspend fun insert(thing: String, data: JsonElement): JsonElement =
        rpc("insert", listOf(JsonPrimitive(thing), data))

    public suspend fun insertResult(thing: String, data: JsonElement): Result<JsonElement> = runCatching {
        insert(thing, data)
    }

    public suspend fun update(thing: String, data: JsonElement? = null): JsonElement {
        val params = buildList {
            add(JsonPrimitive(thing))
            if (data != null) {
                add(data)
            }
        }
        return rpc("update", params)
    }

    public suspend fun updateResult(thing: String, data: JsonElement? = null): Result<JsonElement> = runCatching {
        update(thing, data)
    }

    public suspend fun merge(thing: String, data: JsonElement? = null): JsonElement {
        val params = buildList {
            add(JsonPrimitive(thing))
            if (data != null) {
                add(data)
            }
        }
        return rpc("merge", params)
    }

    public suspend fun mergeResult(thing: String, data: JsonElement? = null): Result<JsonElement> = runCatching {
        merge(thing, data)
    }

    public suspend fun patch(thing: String, patches: JsonElement, diff: Boolean? = null): JsonElement {
        val params = buildList {
            add(JsonPrimitive(thing))
            add(patches)
            if (diff != null) {
                add(JsonPrimitive(diff))
            }
        }
        return rpc("patch", params)
    }

    public suspend fun patchResult(thing: String, patches: JsonElement, diff: Boolean? = null): Result<JsonElement> = runCatching {
        patch(thing, patches, diff)
    }

    public suspend fun delete(thing: String): JsonElement = rpc("delete", listOf(JsonPrimitive(thing)))
    public suspend fun deleteResult(thing: String): Result<JsonElement> = runCatching { delete(thing) }

    public suspend fun relate(inRecord: String, relation: String, outRecord: String, data: JsonElement? = null): JsonElement {
        val params = buildList {
            add(JsonPrimitive("$inRecord->$relation->$outRecord"))
            if (data != null) {
                add(data)
            }
        }
        return rpc("relate", params)
    }

    public suspend fun relateResult(
        inRecord: String,
        relation: String,
        outRecord: String,
        data: JsonElement? = null,
    ): Result<JsonElement> = runCatching {
        relate(inRecord, relation, outRecord, data)
    }

    public suspend fun run(function: String, args: List<JsonElement> = emptyList()): JsonElement {
        return rpc("run", listOf(JsonPrimitive(function)) + args)
    }

    public suspend fun runResult(function: String, args: List<JsonElement> = emptyList()): Result<JsonElement> = runCatching {
        run(function, args)
    }

    public suspend fun live(query: String, vars: JsonObject? = null): LiveQuerySubscription = withAutoAuthRetry {
        prepareWsContext()
        wsDispatcher.live(query = query, vars = vars, executeRpc = ::executeWsRpc)
    }

    public suspend fun liveResult(query: String, vars: JsonObject? = null): Result<LiveQuerySubscription> = runCatching {
        live(query, vars)
    }

    public suspend fun kill(liveQueryId: String): JsonElement = withAutoAuthRetry {
        prepareWsContext()
        val response = executeWsRpc("kill", listOf(JsonPrimitive(liveQueryId)))
        responseToResult(response)
    }

    public suspend fun killResult(liveQueryId: String): Result<JsonElement> = runCatching { kill(liveQueryId) }

    public inline fun <reified T> decode(element: JsonElement): T =
        config.json.decodeFromJsonElement(element)

    public suspend inline fun <reified T> queryAs(sql: String, vars: JsonObject? = null): T =
        decode(query(sql, vars))

    public suspend inline fun <reified T> selectAs(thing: String): T = decode(select(thing))
    public suspend inline fun <reified T> createAs(thing: String, data: JsonElement? = null): T = decode(create(thing, data))
    public suspend inline fun <reified T> insertAs(thing: String, data: JsonElement): T = decode(insert(thing, data))
    public suspend inline fun <reified T> updateAs(thing: String, data: JsonElement? = null): T = decode(update(thing, data))
    public suspend inline fun <reified T> mergeAs(thing: String, data: JsonElement? = null): T = decode(merge(thing, data))
    public suspend inline fun <reified T> patchAs(thing: String, patches: JsonElement, diff: Boolean? = null): T =
        decode(patch(thing, patches, diff))

    public suspend inline fun <reified T> deleteAs(thing: String): T = decode(delete(thing))

    public override fun close() {
        scope.launch {
            wsDispatcher.close()
            if (ownsHttpClient) {
                httpClient.close()
            }
        }
    }

    private suspend fun executeHttpRpc(
        method: String,
        params: List<JsonElement>,
    ): JsonElement {
        val request = SurrealRpcRequest(id = randomRequestId(), method = method, params = params)
        val endpoint = normalizeRpcEndpoint(config.httpEndpoint)
        val payload = codec.encodeHttpPayload(request)
        val contentType = codec.contentTypeHeader(config.codec)
        val headerMap = requestHeaders()

        val response = httpClient.post(endpoint) {
            headers {
                append(HttpHeaders.ContentType, contentType)
                append(HttpHeaders.Accept, contentType)
                for ((header, value) in headerMap) {
                    append(header, value)
                }
            }
            setBody(payload)
        }

        val bytes = response.body<ByteArray>()
        if (!response.status.isSuccess()) {
            throw SurrealTransportException(
                "SurrealDB request failed with HTTP ${response.status.value}: ${bytes.decodeToString()}"
            )
        }

        val rpcResponse = codec.decodeHttpPayload(bytes)
        return responseToResult(rpcResponse)
    }

    private suspend fun executeWsRpc(
        method: String,
        params: List<JsonElement>,
    ): SurrealRpcResponse {
        val response = wsDispatcher.rpc(method = method, params = params)
        response.error?.let { throw mapError(it) }
        return response
    }

    private suspend fun prepareWsContext() {
        val snapshot = stateMutex.withLock {
            Triple(authToken, namespace, database)
        }

        val token = snapshot.first
        val namespace = snapshot.second
        val database = snapshot.third

        if (token != null) {
            executeWsRpc("authenticate", listOf(JsonPrimitive(token)))
        }
        if (namespace != null && database != null) {
            executeWsRpc("use", listOf(JsonPrimitive(namespace), JsonPrimitive(database)))
        }
    }

    private fun responseToResult(response: SurrealRpcResponse): JsonElement {
        response.error?.let { throw mapError(it) }
        return response.result ?: JsonNull
    }

    private fun mapError(error: SurrealRpcError): SurrealRpcException {
        return if (isAuthError(error)) {
            SurrealAuthenticationException(code = error.code, message = error.message, data = error.data)
        } else {
            SurrealRpcException(code = error.code, message = error.message, data = error.data)
        }
    }

    private fun isAuthError(error: SurrealRpcError): Boolean {
        val message = error.message.lowercase()
        return message.contains("auth") || message.contains("token") || message.contains("signin") || error.code == -32000
    }

    private suspend fun <T> withAutoAuthRetry(
        allowRetry: Boolean = true,
        block: suspend () -> T,
    ): T {
        return try {
            block()
        } catch (cause: SurrealAuthenticationException) {
            if (!allowRetry || !config.autoAuthenticate) {
                throw cause
            }

            val provider = config.credentialProvider ?: throw cause
            val credential = provider() ?: throw cause
            applyAuthInput(credential)
            withAutoAuthRetry(allowRetry = false, block = block)
        }
    }

    private suspend fun applyAuthInput(authInput: SurrealAuthInput) {
        when (authInput) {
            is SurrealAuthInput.SignIn -> {
                val result = executeHttpRpc("signin", listOf(authInput.params))
                updateTokenFromResult(result)
            }

            is SurrealAuthInput.Token -> {
                executeHttpRpc("authenticate", listOf(JsonPrimitive(authInput.token)))
                stateMutex.withLock {
                    authToken = authInput.token
                }
            }
        }
    }

    private suspend fun updateTokenFromResult(result: JsonElement) {
        val token = extractToken(result)
        if (token != null) {
            stateMutex.withLock {
                authToken = token
            }
        }
    }

    private fun extractToken(result: JsonElement): String? {
        return when {
            result is JsonPrimitive && result.isString -> result.content
            result is JsonObject -> {
                val tokenField = result["token"] ?: result["jwt"]
                tokenField?.jsonPrimitive?.content
            }

            else -> null
        }
    }

    private suspend fun requestHeaders(): Map<String, String> = stateMutex.withLock {
        buildMap {
            authToken?.let { put(HttpHeaders.Authorization, "Bearer $it") }
            namespace?.let { put("Surreal-NS", it) }
            database?.let { put("Surreal-DB", it) }
        }
    }

    private fun defaultHttpClient(config: SurrealClientConfig): HttpClient {
        return HttpClient {
            install(WebSockets)
            install(HttpTimeout) {
                requestTimeoutMillis = config.requestTimeoutMillis
                connectTimeoutMillis = config.requestTimeoutMillis
                socketTimeoutMillis = config.requestTimeoutMillis
            }
            expectSuccess = false
        }
    }
}
