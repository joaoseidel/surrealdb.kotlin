package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.live.LiveMode
import com.surrealdb.kotlin.api.live.LiveQueryEvent
import com.surrealdb.kotlin.api.query.ReturnMode
import com.surrealdb.kotlin.api.query.create
import com.surrealdb.kotlin.api.query.delete
import com.surrealdb.kotlin.api.query.insert
import com.surrealdb.kotlin.api.query.merge
import com.surrealdb.kotlin.api.query.patch
import com.surrealdb.kotlin.api.query.relate
import com.surrealdb.kotlin.api.query.surql
import com.surrealdb.kotlin.api.query.update
import com.surrealdb.kotlin.api.query.upsert
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class SurrealJvmIntegrationTest {
    @Test
    fun `runs full rpc and live integration flow`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("SURREAL_RUN_INTEGRATION") == "true")
            val endpoint = System.getenv("SURREAL_JVM_ENDPOINT") ?: "http://127.0.0.1:8000"

            val client = Surreal(Surreal.Config(url = endpoint))
            val db = client.session()
            try {
                assertTrue(Feature.ExportImport in client.features)

                db.signin(Credentials.RootUser("root", "root"))
                db.use(Namespace("main"), Database("main"))
                db.ping()
                db.version()
                assertNotNull(db.whoami())

                db.query(surql("DEFINE TABLE person SCHEMALESS"))
                db.query(surql("DEFINE TABLE likes SCHEMALESS"))
                // Start from an empty table rather than trusting the cleanup at the end of
                // this test, which does not run when an assertion above it fails.
                db.query(surql("DELETE person"))
                db.query(surql("DELETE likes"))

                db
                    .create(RecordId("person", "chiru"))
                    .content(
                        buildJsonObject {
                            put("name", JsonPrimitive("Chiru"))
                            put("age", JsonPrimitive(30))
                        },
                    ).await()

                db
                    .insert(
                        Table("person"),
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive("person:ada"))
                                    put("name", JsonPrimitive("Ada"))
                                },
                            )
                        },
                    ).await()

                db
                    .upsert(RecordId("person", "chiru"))
                    .content(
                        buildJsonObject {
                            put("name", JsonPrimitive("Chiru B"))
                            put("age", JsonPrimitive(31))
                        },
                    ).await()

                db
                    .update(RecordId("person", "chiru"))
                    .content(buildJsonObject { put("name", JsonPrimitive("Chiru C")) })
                    .await()

                db
                    .merge(
                        RecordId("person", "chiru"),
                        buildJsonObject { put("active", JsonPrimitive(true)) },
                    ).await()

                db
                    .patch(
                        RecordId("person", "chiru"),
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("op", JsonPrimitive("replace"))
                                    put("path", JsonPrimitive("/name"))
                                    put("value", JsonPrimitive("Chiru D"))
                                },
                            ),
                        ),
                    ).await()

                val patchedBefore =
                    db
                        .patch(
                            RecordId("person", "chiru"),
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("op", JsonPrimitive("replace"))
                                        put("path", JsonPrimitive("/name"))
                                        put("value", JsonPrimitive("Chiru E"))
                                    },
                                ),
                            ),
                        ).returnMode(ReturnMode.Before)
                        .await()
                assertEquals(
                    "Chiru D",
                    patchedBefore
                        .single()
                        .content["name"]
                        ?.jsonPrimitive
                        ?.content,
                )

                db
                    .relate(
                        RecordId("person", "chiru"),
                        Table("likes"),
                        RecordId("person", "ada"),
                    ).content(buildJsonObject { put("strength", JsonPrimitive("high")) })
                    .await()

                db.`let`("tb", "person")
                val queryResult = db.query(surql("SELECT * FROM type::table(\$tb)"))
                assertTrue(queryResult.jsonArray.isNotEmpty())
                db.unset("tb")

                val sessionB = client.session()
                sessionB.signin(Credentials.RootUser("root", "root"))
                sessionB.use(Namespace("main"), Database("main"))
                // Both sessions see the same data — compare the inner result, not the
                // outer envelope (which includes per-call timing).
                val countA =
                    db
                        .query(surql("SELECT count() FROM person GROUP ALL"))
                        .jsonArray[0]
                        .jsonObject["result"]
                val countB =
                    sessionB
                        .query(surql("SELECT count() FROM person GROUP ALL"))
                        .jsonArray[0]
                        .jsonObject["result"]
                assertEquals(countA.toString(), countB.toString())
                client.closeSession(sessionB)

                db.delete(RecordId("person", "chiru")).await()
                db.invalidate()
            } finally {
                client.close()
            }
        }

    @Test
    fun `client side transactions over websocket commit and cancel`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("SURREAL_RUN_INTEGRATION") == "true")
            val httpEndpoint = System.getenv("SURREAL_JVM_ENDPOINT") ?: "http://127.0.0.1:8000"
            val wsEndpoint = httpEndpoint.replace("http://", "ws://").replace("https://", "wss://")

            val client = Surreal(Surreal.Config(url = wsEndpoint, autoConnect = true))
            val db = client.session()
            try {
                assertTrue(Feature.Transactions in client.features)

                db.signin(Credentials.RootUser("root", "root"))
                db.use(Namespace("main"), Database("main"))
                db.query(surql("DEFINE TABLE tx_person SCHEMALESS"))
                db.query(surql("DELETE tx_person"))

                db.transaction {
                    create(RecordId("tx_person", "alice"))
                        .content(buildJsonObject { put("name", JsonPrimitive("Alice")) })
                        .await()
                }
                val afterCommit =
                    db
                        .query(surql("SELECT * FROM tx_person"))
                        .jsonArray[0]
                        .jsonObject["result"]!!
                        .jsonArray
                assertEquals(1, afterCommit.size)

                val cancelled =
                    runCatching {
                        db.transaction {
                            create(RecordId("tx_person", "bob"))
                                .content(buildJsonObject { put("name", JsonPrimitive("Bob")) })
                                .await()
                            error("bail out")
                        }
                    }
                assertTrue(cancelled.isFailure)
                val afterCancel =
                    db
                        .query(surql("SELECT * FROM tx_person"))
                        .jsonArray[0]
                        .jsonObject["result"]!!
                        .jsonArray
                assertEquals(1, afterCancel.size, "cancel should have rolled back bob")

                val tx = db.beginTransaction()
                tx
                    .create(RecordId("tx_person", "carol"))
                    .content(buildJsonObject { put("name", JsonPrimitive("Carol")) })
                    .await()
                tx.commit()
                val afterExplicit =
                    db
                        .query(surql("SELECT * FROM tx_person"))
                        .jsonArray[0]
                        .jsonObject["result"]!!
                        .jsonArray
                assertEquals(2, afterExplicit.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun `live queries and connection events over websocket`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("SURREAL_RUN_INTEGRATION") == "true")
            val httpEndpoint = System.getenv("SURREAL_JVM_ENDPOINT") ?: "http://127.0.0.1:8000"
            val wsEndpoint = httpEndpoint.replace("http://", "ws://").replace("https://", "wss://")

            val client = Surreal(Surreal.Config(url = wsEndpoint, autoConnect = true))
            val db = client.session()
            try {
                assertTrue(Feature.LiveQueries in client.features)

                val firstEvent =
                    withTimeoutOrNull(5_000) {
                        client.connectionEvents.firstOrNull { it is ConnectionEvent.Connected }
                    }
                assertNotNull(firstEvent)

                db.signin(Credentials.RootUser("root", "root"))
                db.use(Namespace("main"), Database("main"))
                db.query(surql("DEFINE TABLE live_person SCHEMALESS"))
                db.query(surql("DELETE live_person"))

                val subscription = db.live(Table("live_person"))
                val diffs = db.live(Table("live_person"), LiveMode.Diffs)

                // Wait on the engine's own record of the subscription rather than sleeping
                // and hoping — this is what activeLiveQueries is for.
                withTimeout(5_000) { client.activeLiveQueries.first { subscription.id in it } }
                withTimeout(5_000) { client.activeLiveQueries.first { diffs.id in it } }

                db
                    .create(RecordId("live_person", "one"))
                    .content(buildJsonObject { put("name", JsonPrimitive("Live")) })
                    .await()

                val event = withTimeout(10_000) { subscription.events.first() }
                assertEquals("CREATE", event.action)
                assertNotNull(event.result.jsonObject["name"])

                val diffEvent = withTimeout(10_000) { diffs.events.first() }
                assertEquals("CREATE", diffEvent.action)
                val firstOp =
                    diffEvent.result.jsonArray
                        .first()
                        .jsonObject["op"]
                assertEquals("replace", firstOp?.jsonPrimitive?.content)

                db.kill(diffs.id)
                diffs.cancel()

                db.kill(subscription.id)
                assertTrue(
                    subscription.id !in client.activeLiveQueries.value,
                    "kill(id) must stop tracking the query even though it never sees the subscription",
                )

                subscription.cancel()
                db.delete(RecordId("live_person", "one")).await()
            } finally {
                client.close()
            }
        }

    @Test
    fun `flow scoped live queries filter server side and kill on cancellation`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("SURREAL_RUN_INTEGRATION") == "true")
            val httpEndpoint = System.getenv("SURREAL_JVM_ENDPOINT") ?: "http://127.0.0.1:8000"
            val wsEndpoint = httpEndpoint.replace("http://", "ws://").replace("https://", "wss://")

            val client = Surreal(Surreal.Config(url = wsEndpoint, autoConnect = true))
            val db = client.session()
            try {
                db.signin(Credentials.RootUser("root", "root"))
                db.use(Namespace("main"), Database("main"))
                db.query(surql("DEFINE TABLE live_book SCHEMALESS"))
                db.query(surql("DELETE live_book"))

                val received = Channel<LiveQueryEvent<JsonElement>>(Channel.UNLIMITED)
                val collector =
                    launch {
                        db
                            .liveEvents<JsonElement>("SELECT * FROM live_book WHERE pages > 100")
                            .collect { received.send(it) }
                    }

                // The query exists only once collection has begun, so wait for it rather
                // than racing the writes below against the LIVE SELECT.
                val queryId = withTimeout(5_000) { client.activeLiveQueries.first { it.isNotEmpty() } }.single()

                db
                    .create(RecordId("live_book", "pamphlet"))
                    .content(buildJsonObject { put("pages", JsonPrimitive(10)) })
                    .await()
                db
                    .create(RecordId("live_book", "tome"))
                    .content(buildJsonObject { put("pages", JsonPrimitive(500)) })
                    .await()

                // Notifications arrive in write order on one socket, so seeing the tome's
                // means the pamphlet's would already have arrived had the server sent one.
                val event = withTimeout(10_000) { received.receive() }
                assertTrue(event is LiveQueryEvent.Created, "expected a Created event, got $event")
                assertEquals(RecordId("live_book", "tome"), event.record)
                assertFalse(
                    received.tryReceive().isSuccess,
                    "the WHERE filter must be evaluated server side — the pamphlet should never have been sent",
                )

                collector.cancelAndJoin()
                withTimeout(5_000) { client.activeLiveQueries.first { queryId !in it } }

                db.delete(RecordId("live_book", "pamphlet")).await()
                db.delete(RecordId("live_book", "tome")).await()
            } finally {
                client.close()
            }
        }
}
