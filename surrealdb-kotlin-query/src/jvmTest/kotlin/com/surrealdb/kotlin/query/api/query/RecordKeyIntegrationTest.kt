package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.RecordIdRange
import com.surrealdb.kotlin.core.api.data.RecordKey
import com.surrealdb.kotlin.core.api.data.Row
import com.surrealdb.kotlin.core.api.live.LiveQueryEvent
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.get
import com.surrealdb.kotlin.query.api.integrationEndpoint
import com.surrealdb.kotlin.query.api.integrationTestConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.uuid.Uuid

private object Items : Table("rk_item") {
    val id = recordId()
    val kind by field<String>()
    val link by field<RecordId>()
}

@Serializable
private data class Item(
    val id: RecordId,
    val kind: String,
)

private const val UUID_TEXT = "0196a3c2-1234-7abc-8def-0123456789ab"
private val uuid = Uuid.parse(UUID_TEXT)

private fun item(key: RecordKey): RecordId = RecordId(Items.tableName, key)

private suspend fun Query.theRecord(): Row = awaitSingleOrNull() ?: error("the statement answered with no record")

private fun onServer(
    endpoint: String = integrationEndpoint(),
    block: suspend (Surreal, Session) -> Unit,
) {
    runBlocking {
        val client = Surreal(Surreal.Config(url = endpoint))
        val db = client.session()
        try {
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            db.query(surql("DEFINE TABLE OVERWRITE ${Items.tableName} SCHEMALESS"))
            db.query(surql("DELETE ${Items.tableName}"))

            block(client, db)
        } finally {
            client.close()
        }
    }
}

private fun overWebSocket(block: suspend (Surreal, Session) -> Unit) =
    onServer(integrationEndpoint().replace("http://", "ws://").replace("https://", "wss://"), block)

/**
 * Every kind of key, against SurrealDB 3.2.4: created through a RecordId,
 * read back as the same RecordId, found through a where clause, decoded
 * into a class, and told apart from the record whose key merely looks the
 * same. The pairs that must differ are the ones the server keeps as two
 * records: `rk_item:1` against `` rk_item:`1` ``, `rk_item:u'…'` against
 * `` rk_item:`…` ``.
 */
class RecordKeyIntegrationTest :
    ShouldSpec(
        {
            defaultTestConfig = integrationTestConfig

            suspend fun Session.roundTrip(id: RecordId) {
                create(Items[id.key]).set { it[kind] = id.key.toString() }.await()

                select(id).theRecord()[Items.id] shouldBe id
                select(Items).where { Items.id eq id }.await().map { it[Items.id] } shouldBe listOf(id)
                select(id).decodeAs<Item>().awaitSingleOrNull() shouldBe Item(id, id.key.toString())
                delete(id).await()
                select(id).awaitSingleOrNull().shouldBeNull()
            }

            context("a text key") {
                should("round-trip a bare key, a quoted key and one that reads as a number") {
                    onServer { _, db ->
                        listOf("alice", "with-dash", "1", "1e5").forEach { db.roundTrip(item(RecordKey.Text(it))) }
                    }
                }

                should("keep a colon inside the key, which the server otherwise reads as a whole record id") {
                    onServer { _, db ->
                        db.create(Items[1]).set { it[kind] = "integer" }.await()

                        db.roundTrip(item(RecordKey.Text("a:b")))
                        db.roundTrip(item(RecordKey.Text("other:1")))

                        db.select(Items[1]).theRecord()[Items.kind] shouldBe "integer"
                    }
                }

                should("name the string record and not the integer one, so RecordId(table, \"1\") keeps its meaning") {
                    onServer { _, db ->
                        db.create(Items["1"]).set { it[kind] = "text" }.await()
                        db.create(Items[1]).set { it[kind] = "integer" }.await()

                        db.select(Items["1"]).theRecord()[Items.kind] shouldBe "text"
                        db.select(Items[1]).theRecord()[Items.kind] shouldBe "integer"
                        db
                            .select(Items)
                            .await()
                            .map { it[Items.id] }
                            .toSet() shouldBe
                            setOf(item(RecordKey.Text("1")), item(RecordKey.Integer(1)))
                    }
                }

                should("keep uuid-shaped text as text, since the server would coerce a bound string to a uuid key") {
                    onServer { _, db ->
                        db.roundTrip(item(RecordKey.Text(UUID_TEXT)))

                        db.create(Items[UUID_TEXT]).set { it[kind] = "text" }.await()

                        db.select(Items[uuid]).awaitSingleOrNull().shouldBeNull()
                    }
                }
            }

            context("an integer key") {
                should("round-trip, negative values included") {
                    onServer { _, db ->
                        db.roundTrip(item(RecordKey.Integer(7)))
                        db.roundTrip(item(RecordKey.Integer(-5)))
                        db.roundTrip(item(RecordKey.Integer(Long.MAX_VALUE)))
                    }
                }

                should("select over an integer range, which orders by value and skips the string keys") {
                    onServer { _, db ->
                        listOf(1L, 9L, 10L, 100L).forEach { db.create(Items[it]).set { it[kind] = "integer" }.await() }
                        db.create(Items["5"]).set { it[kind] = "text" }.await()

                        val inRange =
                            RecordIdRange(Items.tableName, start = RecordKey.Integer(2), end = RecordKey.Integer(100))

                        db.select(inRange).await().map { it[Items.id] } shouldBe
                            listOf(item(RecordKey.Integer(9)), item(RecordKey.Integer(10)))
                    }
                }
            }

            context("a uuid key") {
                should("round-trip and differ from the string key holding the same text") {
                    onServer { _, db ->
                        db.roundTrip(item(RecordKey.Uuid(uuid)))

                        db.create(Items[uuid]).set { it[kind] = "uuid" }.await()

                        db.select(Items[UUID_TEXT]).awaitSingleOrNull().shouldBeNull()
                        db.select(Items[uuid]).theRecord()[Items.kind] shouldBe "uuid"
                    }
                }
            }

            context("an array key") {
                val ab1 =
                    item(
                        RecordKey.Array(
                            buildJsonArray {
                                add("a")
                                add(1)
                            },
                        ),
                    )
                val withUuidText =
                    item(
                        RecordKey.Array(
                            buildJsonArray {
                                add("x")
                                add(UUID_TEXT)
                                add(5)
                            },
                        ),
                    )

                should("round-trip, nested values included") {
                    onServer { _, db ->
                        db.roundTrip(ab1)
                        db.roundTrip(
                            item(
                                RecordKey.Array(
                                    buildJsonArray {
                                        add(buildJsonObject { put("a", JsonPrimitive(1)) })
                                        add(
                                            buildJsonArray {
                                                add(1)
                                                add(2)
                                            },
                                        )
                                    },
                                ),
                            ),
                        )
                        db.roundTrip(item(RecordKey.Array(buildJsonArray { })))
                    }
                }

                should(
                    "reach the same record as a content link and as a target, though the server coerces a uuid-shaped element",
                ) {
                    onServer { _, db ->
                        db.create(Items[withUuidText.key]).set { it[kind] = "target" }.await()
                        db.create(Items["via-content"]).content { it[link] = withUuidText }.await()
                        db.create(Items["via-set"]).set { it[link] = withUuidText }.await()

                        val viaContent = db.select(Items["via-content"]).theRecord()[Items.link]
                        val viaSet = db.select(Items["via-set"]).theRecord()[Items.link]

                        viaContent shouldBe withUuidText
                        viaSet shouldBe withUuidText
                        db.select(viaContent).theRecord()[Items.kind] shouldBe "target"
                    }
                }

                should("select over an array range") {
                    onServer { _, db ->
                        db.create(Items[ab1.key]).set { it[kind] = "array" }.await()
                        val b1 =
                            RecordKey.Array(
                                buildJsonArray {
                                    add("b")
                                    add(1)
                                },
                            )
                        db.create(Items[b1]).set { it[kind] = "array" }.await()

                        val range =
                            RecordIdRange(
                                Items.tableName,
                                start = RecordKey.Array(buildJsonArray { add("a") }),
                                end =
                                    RecordKey.Array(
                                        buildJsonArray {
                                            add("a")
                                            add(2)
                                        },
                                    ),
                                includeEnd = true,
                            )

                        db.select(range).await().map { it[Items.id] } shouldBe listOf(ab1)
                    }
                }
            }

            context("an object key") {
                should("round-trip, and equal the same fields sent in another order, because the server sorts them") {
                    onServer { _, db ->
                        val sorted =
                            item(
                                RecordKey.Object(
                                    buildJsonObject {
                                        put("a", JsonPrimitive(1))
                                        put("b", JsonPrimitive("x"))
                                    },
                                ),
                            )
                        val reversed =
                            item(
                                RecordKey.Object(
                                    buildJsonObject {
                                        put("b", JsonPrimitive("x"))
                                        put("a", JsonPrimitive(1))
                                    },
                                ),
                            )

                        db.roundTrip(sorted)
                        db.create(Items[reversed.key]).set { it[kind] = "object" }.await()

                        db.select(sorted).theRecord()[Items.id] shouldBe sorted
                        db.select(reversed).theRecord()[Items.id] shouldBe reversed
                        db.select(Items).await().map { it[Items.id] } shouldBe listOf(sorted)
                    }
                }
            }

            context("a text range") {
                should("take bounds that contain a colon, which only the cast keeps as text") {
                    onServer { _, db ->
                        db.create(Items["a:b"]).set { it[kind] = "text" }.await()
                        db.create(Items["b"]).set { it[kind] = "text" }.await()

                        val range =
                            RecordIdRange(Items.tableName, start = RecordKey.Text("a:a"), end = RecordKey.Text("a:z"))

                        db.select(range).await().map { it[Items.id] } shouldBe listOf(item(RecordKey.Text("a:b")))
                        db.select(RecordIdRange(Items.tableName, start = RecordKey.Text("c"))).await().shouldBeEmpty()
                    }
                }
            }

            context("a live query") {
                should("carry the kind of the key in the event's record, so an integer key is not its digits as text") {
                    overWebSocket { client, db ->
                        val received = Channel<LiveQueryEvent<JsonElement>>(Channel.UNLIMITED)
                        val collector =
                            launch {
                                db
                                    .liveEvents<JsonElement>(
                                        "LIVE SELECT * FROM ${Items.tableName}",
                                    ).collect { received.send(it) }
                            }
                        withTimeout(5_000) { client.activeLiveQueries.first { it.isNotEmpty() } }

                        db.create(Items[42]).set { it[kind] = "integer" }.await()
                        db.create(Items[uuid]).set { it[kind] = "uuid" }.await()

                        withTimeout(
                            10_000,
                        ) {
                            received.receive()
                        }.shouldBeInstanceOf<LiveQueryEvent.Created<JsonElement>>().record shouldBe
                            item(RecordKey.Integer(42))
                        withTimeout(
                            10_000,
                        ) {
                            received.receive()
                        }.shouldBeInstanceOf<LiveQueryEvent.Created<JsonElement>>().record shouldBe
                            item(RecordKey.Uuid(uuid))

                        collector.cancelAndJoin()
                    }
                }
            }
        },
    )
