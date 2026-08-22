package com.surrealdb.kotlin.query.runtime.engine

import com.surrealdb.kotlin.core.api.ReconnectConfig
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A live query the server was asked to start, and the id it gave back. */
internal data class IssuedLiveQuery(
    val method: String,
    val target: String,
    val liveQueryId: String,
    val bindings: JsonObject?,
)

/**
 * Enough of the SurrealDB websocket RPC to test what the client does when the
 * socket goes away: it answers `live`, `query`, `begin` and the session
 * methods, hands out a fresh subscription id every time, and drops the
 * connection underneath a running client on demand.
 *
 * A real socket is required because a subscription surviving a reconnect only
 * exists in the interaction between
 * the connect loop and the router.
 *
 * With [notifyOnStart] set, every live query it starts is announced with one
 * notification sent *before* the reply naming the subscription. That is the drop
 * race made deterministic: a client that only starts listening once it has read
 * the id has already missed the frame.
 */
internal class FakeSurrealServer(
    private val liveQueriesBeforeRejecting: Int = Int.MAX_VALUE,
    private val notifyOnStart: String? = null,
) {
    private val json = Json
    private val ids = AtomicInteger()
    private val started = AtomicInteger()

    /** Every live query the server was asked to start, oldest first. */
    val issued: MutableList<IssuedLiveQuery> = CopyOnWriteArrayList()

    /** Every JSON-RPC envelope the server received, oldest first. */
    val received: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** One entry per accepted websocket, so a test can wait for the reconnect. */
    val connections: Channel<Unit> = Channel(Channel.UNLIMITED)

    private val announcements = ConcurrentLinkedQueue<String>()

    @Volatile
    private var socket: DefaultWebSocketSession? = null

    private val server =
        embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/rpc") {
                    socket = this
                    connections.trySend(Unit)
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val response = reply(frame.readText())
                            announce()
                            send(Frame.Text(response))
                        }
                    }
                }
            }
        }

    var port: Int = 0
        private set

    fun start() {
        server.start(wait = false)
        port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
    }

    fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    /** Drop the socket the client is using, as a network failure would. */
    suspend fun dropConnection() {
        socket?.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "dropped"))
    }

    /** Push a notification for [liveQueryId] over whichever socket is current. */
    suspend fun notify(
        liveQueryId: String,
        payload: String,
    ) {
        val notification =
            buildJsonObject {
                put(
                    "result",
                    buildJsonObject {
                        put("action", "CREATE")
                        put("id", liveQueryId)
                        put("record", "book:$payload")
                        put("result", buildJsonObject { put("title", payload) })
                    },
                )
            }

        socket?.send(Frame.Text(json.encodeToString(JsonElement.serializer(), notification)))
    }

    suspend fun notifyAge(
        liveQueryId: String,
        recordId: String,
        age: Int,
    ) {
        val issuedQuery = issued.last { it.liveQueryId == liveQueryId }
        val minimum =
            issuedQuery.bindings
                ?.get("_1")
                ?.jsonPrimitive
                ?.int
                ?: error("No minimum-age binding")
        if (age < minimum) return

        val notification =
            buildJsonObject {
                put(
                    "result",
                    buildJsonObject {
                        put("action", "CREATE")
                        put("id", liveQueryId)
                        put("record", "user:$recordId")
                        put("result", buildJsonObject { put("age", age) })
                    },
                )
            }

        socket?.send(Frame.Text(json.encodeToString(JsonElement.serializer(), notification)))
    }

    suspend fun notifyKilled(liveQueryId: String) {
        val notification =
            buildJsonObject {
                put(
                    "result",
                    buildJsonObject {
                        put("action", "KILLED")
                        put("id", liveQueryId)
                        put("record", JsonNull)
                        put("result", JsonNull)
                    },
                )
            }

        socket?.send(Frame.Text(json.encodeToString(JsonElement.serializer(), notification)))
    }

    private suspend fun announce() {
        while (true) {
            val liveQueryId = announcements.poll() ?: return
            notify(liveQueryId, checkNotNull(notifyOnStart))
        }
    }

    private sealed interface Outcome {
        data class Ok(
            val result: JsonElement,
        ) : Outcome

        data object Rejected : Outcome
    }

    private fun reply(text: String): String {
        val request = Json.parseToJsonElement(text).jsonObject
        received += request
        val requestId = request["id"]?.jsonPrimitive?.content
        val method = request["method"]?.jsonPrimitive?.content.orEmpty()
        val params = request["params"]?.jsonArray ?: JsonArray(emptyList())
        val firstParam =
            params
                .firstOrNull()
                ?.jsonPrimitive
                ?.content
                .orEmpty()

        val outcome =
            when (method) {
                "live" -> startLiveQuery(method, firstParam)
                "query" -> runStatement(firstParam, params.getOrNull(1) as? JsonObject)
                "begin" -> Outcome.Ok(JsonPrimitive("txn-${ids.incrementAndGet()}"))
                else -> Outcome.Ok(JsonNull)
            }

        val response =
            buildJsonObject {
                if (requestId != null) put("id", requestId)
                when (outcome) {
                    is Outcome.Ok -> {
                        put("result", outcome.result)
                    }

                    Outcome.Rejected -> {
                        put(
                            "error",
                            buildJsonObject {
                                put("code", -32000)
                                put("message", "live query rejected")
                            },
                        )
                    }
                }
            }

        return json.encodeToString(JsonElement.serializer(), response)
    }

    private fun startLiveQuery(
        method: String,
        target: String,
        bindings: JsonObject? = null,
    ): Outcome {
        if (started.incrementAndGet() > liveQueriesBeforeRejecting) return Outcome.Rejected

        val liveQueryId = "lq-${ids.incrementAndGet()}"
        issued += IssuedLiveQuery(method, target, liveQueryId, bindings)
        if (notifyOnStart != null) announcements += liveQueryId
        return Outcome.Ok(JsonPrimitive(liveQueryId))
    }

    private fun runStatement(
        sql: String,
        bindings: JsonObject?,
    ): Outcome {
        val result =
            if (sql.startsWith("LIVE ", ignoreCase = true)) {
                when (val started = startLiveQuery("query", sql, bindings)) {
                    is Outcome.Ok -> started.result
                    Outcome.Rejected -> return Outcome.Rejected
                }
            } else {
                JsonNull
            }

        return Outcome.Ok(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("status", "OK")
                        put("result", result)
                    },
                )
            },
        )
    }
}

internal const val PATIENCE_MILLIS = 10_000L

internal suspend fun awaiting(condition: () -> Boolean) {
    withTimeout(PATIENCE_MILLIS) {
        while (!condition()) delay(5)
    }
}

internal fun clientFor(server: FakeSurrealServer) =
    Surreal(
        Surreal.Config(
            url = "ws://127.0.0.1:${server.port}",
            reconnect = ReconnectConfig(initialDelayMillis = 10, maxDelayMillis = 50),
            requestTimeoutMillis = PATIENCE_MILLIS,
        ),
    )

internal fun withServer(
    liveQueriesBeforeRejecting: Int = Int.MAX_VALUE,
    notifyOnStart: String? = null,
    block: suspend CoroutineScope.(FakeSurrealServer, Surreal, Session) -> Unit,
) {
    val server = FakeSurrealServer(liveQueriesBeforeRejecting, notifyOnStart)
    server.start()
    try {
        val client = clientFor(server)
        try {
            runBlocking { block(server, client, client.session()) }
        } finally {
            client.close()
        }
    } finally {
        server.stop()
    }
}
