package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.ReconnectConfig
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.error.SurrealLiveQueryException
import com.surrealdb.kotlin.api.live.LiveNotification
import com.surrealdb.kotlin.api.live.LiveQueryEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

private const val PATIENCE_MILLIS = 10_000L

private suspend fun awaiting(condition: () -> Boolean) {
    withTimeout(PATIENCE_MILLIS) {
        while (!condition()) delay(5)
    }
}

private fun clientFor(server: FakeSurrealServer) =
    Surreal(
        Surreal.Config(
            url = "ws://127.0.0.1:${server.port}",
            // Short delays because the test waits on the reconnect rather than sleeping
            // past it; the defaults would add a quarter second to every case.
            reconnect = ReconnectConfig(initialDelayMillis = 10, maxDelayMillis = 50),
            requestTimeoutMillis = PATIENCE_MILLIS,
        ),
    )

private fun withServer(
    liveQueriesBeforeRejecting: Int = Int.MAX_VALUE,
    notifyOnStart: String? = null,
    block: suspend CoroutineScope.(FakeSurrealServer, Surreal) -> Unit,
) {
    val server = FakeSurrealServer(liveQueriesBeforeRejecting, notifyOnStart)
    server.start()
    try {
        val client = clientFor(server)
        try {
            runBlocking { block(server, client) }
        } finally {
            client.close()
        }
    } finally {
        server.stop()
    }
}

class LiveQueryReconnectTest :
    ShouldSpec({
        context("a live query whose connection drops") {

            should("be started again on the new connection, the server having forgotten it with the session") {
                withServer { server, client ->
                    client.live("book")
                    awaiting { server.issued.size == 1 }

                    server.dropConnection()

                    awaiting { server.issued.size == 2 }
                    server.issued[1].target shouldBe "book"
                }
            }

            should("keep delivering to the same subscription, under the id it was given first") {
                withServer { server, client ->
                    val subscription = client.live("book")
                    val received = mutableListOf<LiveNotification>()
                    val collector: Job = launch { subscription.events.collect { received += it } }

                    server.notify(server.issued[0].liveQueryId, "before")
                    awaiting { received.size == 1 }

                    server.dropConnection()
                    awaiting { server.issued.size == 2 }
                    server.notify(server.issued[1].liveQueryId, "after")
                    awaiting { received.size == 2 }

                    // The server issued two different ids; the collector saw one, because
                    // the second is an implementation detail of having reconnected.
                    server.issued[0].liveQueryId shouldNotBe server.issued[1].liveQueryId
                    received.map { it.liveQueryId } shouldBe listOf(subscription.id, subscription.id)
                    collector.cancel()
                }
            }

            should("stay listed under the id a caller holds, so that id is still one it can kill") {
                withServer { server, client ->
                    val subscription = client.live("book")
                    awaiting { server.issued.size == 1 }

                    server.dropConnection()
                    awaiting { server.issued.size == 2 }

                    client.activeLiveQueries.value shouldContain subscription.id
                }
            }

            should("carry on for a query started as a LIVE SELECT, which the client re-runs as a statement") {
                withServer { server, client ->
                    val events = mutableListOf<LiveQueryEvent<JsonElement>>()
                    val collector = launch { client.liveEvents<JsonElement>("book").collect { events += it } }

                    // Waits for the client to have taken ownership of the query, not just
                    // for the server to have started it: dropping in between replays the
                    // statement as a pending call, which is a different path entirely.
                    awaiting { client.activeLiveQueries.value.isNotEmpty() }
                    val queryId = client.activeLiveQueries.value.single()

                    server.dropConnection()
                    awaiting { server.issued.size == 2 }
                    server.notify(server.issued[1].liveQueryId, "after")
                    awaiting { events.size == 1 }

                    server.issued[1].method shouldBe "query"
                    server.issued[1].target shouldBe "LIVE SELECT * FROM book"
                    events.single().queryId shouldBe queryId
                    collector.cancel()
                }
            }

            should("fail its collector when it cannot be started again, rather than going quiet") {
                withServer(liveQueriesBeforeRejecting = 1) { server, client ->
                    val subscription = client.live("book")
                    awaiting { server.issued.size == 1 }

                    server.dropConnection()

                    shouldThrow<SurrealLiveQueryException> {
                        withTimeout(PATIENCE_MILLIS) { subscription.events.collect { } }
                    }
                    awaiting { subscription.id !in client.activeLiveQueries.value }
                }
            }
        }

        context("a notification the server pushes before the reply naming its subscription") {

            should("reach the subscription, which had no id to attribute it to when the frame arrived") {
                withServer(notifyOnStart = "before-the-reply") { server, client ->
                    val subscription = client.live("book")
                    val received = mutableListOf<LiveNotification>()
                    val collector: Job = launch { subscription.events.collect { received += it } }

                    awaiting { received.size == 1 }

                    server.issued.single().liveQueryId shouldBe subscription.id
                    received.single().liveQueryId shouldBe subscription.id
                    collector.cancel()
                }
            }

            should("reach it again after a reconnect, the same window opening on the re-issued statement") {
                withServer(notifyOnStart = "before-the-reply") { server, client ->
                    val subscription = client.live("book")
                    val received = mutableListOf<LiveNotification>()
                    val collector: Job = launch { subscription.events.collect { received += it } }
                    awaiting { received.size == 1 }

                    server.dropConnection()
                    awaiting { server.issued.size == 2 }
                    awaiting { received.size == 2 }

                    server.issued[0].liveQueryId shouldNotBe server.issued[1].liveQueryId
                    received.map { it.liveQueryId } shouldBe listOf(subscription.id, subscription.id)
                    collector.cancel()
                }
            }

            should("reach the flow form too, whose id filter would otherwise never match the re-issued id") {
                withServer(notifyOnStart = "before-the-reply") { server, client ->
                    val events = mutableListOf<LiveQueryEvent<JsonElement>>()
                    val collector = launch { client.liveEvents<JsonElement>("book").collect { events += it } }

                    awaiting { client.activeLiveQueries.value.isNotEmpty() }
                    val queryId = client.activeLiveQueries.value.single()
                    awaiting { events.size == 1 }

                    server.dropConnection()
                    awaiting { server.issued.size == 2 }
                    awaiting { events.size == 2 }

                    events.map { it.queryId } shouldBe listOf(queryId, queryId)
                    collector.cancel()
                }
            }
        }
    })
