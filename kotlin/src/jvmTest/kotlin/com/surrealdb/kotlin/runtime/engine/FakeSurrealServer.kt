package com.surrealdb.kotlin.runtime.engine

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A live query the server was asked to start, and the id it gave back. */
internal data class IssuedLiveQuery(
    val method: String,
    val target: String,
    val liveQueryId: String,
)

/**
 * Enough of the SurrealDB websocket RPC to test what the client does when the
 * socket goes away: it answers `live`, `query` and the session methods, hands
 * out a fresh subscription id every time, and drops the connection underneath a
 * running client on demand.
 *
 * A real socket rather than a mocked engine, because the behaviour under test —
 * a subscription surviving a reconnect — only exists in the interaction between
 * the connect loop and the router.
 */
internal class FakeSurrealServer(
    private val liveQueriesBeforeRejecting: Int = Int.MAX_VALUE,
) {
    private val json = Json
    private val ids = AtomicInteger()
    private val started = AtomicInteger()

    /** Every live query the server was asked to start, oldest first. */
    val issued: MutableList<IssuedLiveQuery> = CopyOnWriteArrayList()

    /** One entry per accepted websocket, so a test can wait for the reconnect. */
    val connections: Channel<Unit> = Channel(Channel.UNLIMITED)

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
                        if (frame is Frame.Text) send(Frame.Text(reply(frame.readText())))
                    }
                }
            }
        }

    var port: Int = 0
        private set

    fun start() {
        server.start(wait = false)
        port = runBlocking { server.resolvedConnectors().first().port }
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

    private sealed interface Outcome {
        data class Ok(
            val result: JsonElement,
        ) : Outcome

        data object Rejected : Outcome
    }

    private fun reply(text: String): String {
        val request = Json.parseToJsonElement(text).jsonObject
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
                "query" -> runStatement(firstParam)
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
    ): Outcome {
        if (started.incrementAndGet() > liveQueriesBeforeRejecting) return Outcome.Rejected

        val liveQueryId = "lq-${ids.incrementAndGet()}"
        issued += IssuedLiveQuery(method, target, liveQueryId)
        return Outcome.Ok(JsonPrimitive(liveQueryId))
    }

    private fun runStatement(sql: String): Outcome {
        val result =
            if (sql.startsWith("LIVE ", ignoreCase = true)) {
                when (val started = startLiveQuery("query", sql)) {
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
