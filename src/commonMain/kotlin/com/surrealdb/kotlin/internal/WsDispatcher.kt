package com.surrealdb.kotlin.internal

import com.surrealdb.kotlin.SurrealClientConfig
import com.surrealdb.kotlin.error.SurrealProtocolException
import com.surrealdb.kotlin.error.SurrealTransportException
import com.surrealdb.kotlin.live.LiveQuerySubscription
import com.surrealdb.kotlin.model.SurrealLiveNotification
import com.surrealdb.kotlin.model.SurrealRpcRequest
import com.surrealdb.kotlin.model.SurrealRpcResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class WsDispatcher(
    private val client: HttpClient,
    private val config: SurrealClientConfig,
    private val codec: SurrealCodec,
    private val scope: CoroutineScope,
) {
    private val stateMutex = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null

    private val pendingRequests = mutableMapOf<String, CompletableDeferred<SurrealRpcResponse>>()
    private val liveChannels = mutableMapOf<String, Channel<SurrealLiveNotification>>()

    suspend fun rpc(method: String, params: List<kotlinx.serialization.json.JsonElement>): SurrealRpcResponse {
        val activeSession = ensureSession()
        val requestId = randomRequestId()
        val request = SurrealRpcRequest(id = requestId, method = method, params = params)
        val deferred = CompletableDeferred<SurrealRpcResponse>()

        stateMutex.withLock {
            pendingRequests[requestId] = deferred
        }

        try {
            activeSession.send(Frame.Text(codec.encodeWsText(request)))
        } catch (cause: Throwable) {
            stateMutex.withLock {
                pendingRequests.remove(requestId)
            }
            throw SurrealTransportException("Failed to send websocket request", cause)
        }

        return try {
            withTimeout(config.requestTimeoutMillis) {
                deferred.await()
            }
        } finally {
            stateMutex.withLock {
                pendingRequests.remove(requestId)
            }
        }
    }

    suspend fun live(
        query: String,
        vars: JsonObject? = null,
        executeRpc: suspend (String, List<kotlinx.serialization.json.JsonElement>) -> SurrealRpcResponse,
    ): LiveQuerySubscription {
        val params = buildList {
            add(kotlinx.serialization.json.JsonPrimitive(query))
            if (vars != null) {
                add(vars)
            }
        }

        val response = executeRpc("live", params)
        val id = response.result?.jsonPrimitive?.content
            ?: throw SurrealProtocolException("Live query did not return a subscription id")

        val channel = Channel<SurrealLiveNotification>(capacity = Channel.BUFFERED)
        stateMutex.withLock {
            liveChannels[id] = channel
        }

        return LiveQuerySubscription(
            id = id,
            events = channel.receiveAsFlow(),
        ) {
            executeRpc("kill", listOf(kotlinx.serialization.json.JsonPrimitive(id)))
            stateMutex.withLock {
                liveChannels.remove(id)
            }
            channel.close()
        }
    }

    suspend fun close() {
        var closeSession: DefaultClientWebSocketSession? = null
        var closeReader: Job? = null
        var channelsToClose: List<Channel<SurrealLiveNotification>> = emptyList()

        stateMutex.withLock {
            closeSession = session
            closeReader = readerJob
            session = null
            readerJob = null
            channelsToClose = liveChannels.values.toList()
            liveChannels.clear()
            pendingRequests.values.forEach { pending ->
                pending.completeExceptionally(SurrealTransportException("Websocket dispatcher closed"))
            }
            pendingRequests.clear()
        }

        channelsToClose.forEach { it.close() }
        closeReader?.cancel()
        closeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "Client closed"))
    }

    private suspend fun ensureSession(): DefaultClientWebSocketSession {
        stateMutex.withLock {
            val existing = session
            if (existing != null) {
                return existing
            }
        }

        val wsEndpoint = config.wsEndpoint ?: defaultWsEndpoint(config.httpEndpoint)
        val newSession = client.webSocketSession(urlString = wsEndpoint)

        val job = scope.launch {
            readLoop(newSession)
        }

        stateMutex.withLock {
            session = newSession
            readerJob = job
        }

        return newSession
    }

    private suspend fun readLoop(activeSession: DefaultClientWebSocketSession) {
        try {
            for (frame in activeSession.incoming) {
                when (frame) {
                    is Frame.Text -> routeIncoming(codec.decodeWsText(frame.readText()))
                    is Frame.Binary -> routeIncoming(codec.decodeWsText(frame.readBytes().decodeToString()))
                    is Frame.Close -> throw SurrealTransportException("Websocket closed by server")
                    else -> Unit
                }
            }
            throw SurrealTransportException("Websocket closed unexpectedly")
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            failAllPending(cause)
        } finally {
            stateMutex.withLock {
                if (session === activeSession) {
                    session = null
                    readerJob = null
                }
            }
        }
    }

    private suspend fun routeIncoming(response: SurrealRpcResponse) {
        val responseId = response.id
        if (responseId != null) {
            val pending = stateMutex.withLock {
                pendingRequests.remove(responseId)
            }
            pending?.complete(response)
            return
        }

        val live = parseLiveNotification(response)
        if (live != null) {
            val channel = stateMutex.withLock {
                liveChannels[live.liveQueryId]
            }
            channel?.trySend(live)
        }
    }

    private suspend fun failAllPending(cause: Throwable) {
        val mapped = when (cause) {
            is SurrealTransportException -> cause
            else -> SurrealTransportException("Websocket disconnected", cause)
        }

        var requests: List<CompletableDeferred<SurrealRpcResponse>> = emptyList()
        var channels: List<Channel<SurrealLiveNotification>> = emptyList()

        stateMutex.withLock {
            requests = pendingRequests.values.toList()
            channels = liveChannels.values.toList()
            pendingRequests.clear()
            liveChannels.clear()
            session = null
            readerJob = null
        }

        requests.forEach { it.completeExceptionally(mapped) }
        channels.forEach { it.close(mapped) }
    }
}
