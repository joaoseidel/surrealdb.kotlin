package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.live.LiveQueryEvent
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private object Users : Table("user") {
    val age by field<Int>()
}

private fun FakeSurrealServer.killRequests() = received.count { it["method"] == JsonPrimitive("kill") }

class FilteredLiveQueryTest :
    ShouldSpec({
        context("Session.live(table, filter)") {
            should("bind the table and filter, deliver matching rows, and kill once when collection stops") {
                withServer { server, _, db ->
                    val events = mutableListOf<LiveQueryEvent<Row>>()
                    val collector: Job = launch { db.live(Users) { age greaterEq 18 }.collect { events += it } }
                    awaiting { server.issued.size == 1 }

                    val issued = server.issued.single()
                    issued.method shouldBe "query"
                    issued.target shouldBe "LIVE SELECT * FROM type::table(\$_0) WHERE (age >= \$_1)"
                    issued.bindings shouldBe
                        mapOf(
                            "_0" to JsonPrimitive("user"),
                            "_1" to JsonPrimitive(18),
                        )

                    server.notifyAge(issued.liveQueryId, recordId = "minor", age = 17)
                    server.notifyAge(issued.liveQueryId, recordId = "adult", age = 18)
                    awaiting { events.size == 1 }

                    events.single().shouldBeInstanceOf<LiveQueryEvent.Created<Row>>().value[Users.age] shouldBe 18

                    collector.cancelAndJoin()
                    awaiting { server.killRequests() == 1 }
                    server.killRequests() shouldBe 1
                }
            }

            should("reissue the same statement and bindings after reconnecting") {
                withServer { server, client, db ->
                    val events = mutableListOf<LiveQueryEvent<Row>>()
                    val collector: Job = launch { db.live(Users) { age greaterEq 18 }.collect { events += it } }
                    awaiting { client.activeLiveQueries.value.isNotEmpty() }
                    val first = server.issued.single()

                    server.dropConnection()
                    awaiting { server.issued.size == 2 }
                    val replayed = server.issued.last()

                    replayed.method shouldBe "query"
                    replayed.target shouldBe first.target
                    replayed.bindings shouldBe first.bindings

                    server.notifyAge(replayed.liveQueryId, recordId = "adult", age = 21)
                    awaiting { events.size == 1 }
                    events.single().shouldBeInstanceOf<LiveQueryEvent.Created<Row>>().value[Users.age] shouldBe 21

                    collector.cancelAndJoin()
                }
            }

            should("finish on a KILLED notification with null payloads without decoding a row") {
                withServer { server, client, db ->
                    val events = mutableListOf<LiveQueryEvent<Row>>()
                    val collector: Job = launch { db.live(Users) { age greaterEq 18 }.collect { events += it } }
                    awaiting { client.activeLiveQueries.value.isNotEmpty() }

                    server.notifyKilled(server.issued.single().liveQueryId)

                    withTimeout(PATIENCE_MILLIS) { collector.join() }
                    events.shouldContainExactly(emptyList())
                    client.activeLiveQueries.value shouldBe emptySet()
                    server.killRequests() shouldBe 0
                }
            }
        }
    })
