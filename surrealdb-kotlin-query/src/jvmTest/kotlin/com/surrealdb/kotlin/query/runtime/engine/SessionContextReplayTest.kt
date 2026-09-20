package com.surrealdb.kotlin.query.runtime.engine

import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private const val TOKEN_A = "h.eyJJRCI6ImEifQ.s"
private const val TOKEN_B = "h.eyJJRCI6ImIifQ.s"

private fun JsonObject.method(): String = this["method"]?.jsonPrimitive?.content.orEmpty()

private fun JsonObject.params(): JsonArray = this["params"]?.jsonArray ?: JsonArray(emptyList())

private fun List<JsonObject>.uses(): List<JsonArray> = filter { it.method() == "use" }.map { it.params() }

private suspend fun FakeSurrealServer.reconnected() {
    connections.receive()
    dropConnection()
    connections.receive()
}

class SessionContextReplayTest :
    ShouldSpec(
        {
            context("a session whose connection drops") {
                should("re-send a namespace-only use on the new socket before the next request") {
                    withServer { server, _, db ->
                        db.use(Namespace("n"))
                        val before = server.received.size

                        server.reconnected()
                        db.ping()

                        val onNewSocket = server.received.drop(before)
                        onNewSocket.map { it.method() } shouldBe listOf("use", "ping")
                        onNewSocket.uses().single() shouldBe JsonArray(listOf(JsonPrimitive("n"), JsonNull))
                    }
                }

                should("re-send a database selected on its own as the full pair") {
                    withServer { server, _, db ->
                        db.use(Namespace("n"))
                        db.use(Database("d"))
                        val before = server.received.size

                        server.reconnected()
                        db.ping()

                        server.received
                            .drop(before)
                            .uses()
                            .single() shouldBe
                            JsonArray(listOf(JsonPrimitive("n"), JsonPrimitive("d")))
                    }
                }
            }

            context("two sessions on one socket") {
                should(
                    "re-send the pair after the other session's authenticate moved the socket, although the pair is unchanged",
                ) {
                    withServer { server, client, a ->
                        val b = client.session()
                        a.authenticate(TOKEN_A)
                        a.use(Namespace("n"), Database("d"))
                        b.authenticate(TOKEN_B)
                        b.use(Namespace("n"), Database("d"))
                        b.ping()
                        val before = server.received.size

                        a.ping()

                        val forA = server.received.drop(before)
                        forA.map { it.method() } shouldBe listOf("authenticate", "use", "ping")
                        forA[0].params()[0] shouldBe JsonPrimitive(TOKEN_A)
                        forA.uses().single() shouldBe JsonArray(listOf(JsonPrimitive("n"), JsonPrimitive("d")))
                    }
                }

                should(
                    "never send use [null, null] for a session that selected nothing, so a token's own pair survives",
                ) {
                    withServer { server, client, a ->
                        val b = client.session()
                        a.use(Namespace("n"), Database("d"))
                        b.authenticate(TOKEN_B)
                        val before = server.received.size

                        b.ping()

                        server.received
                            .drop(before)
                            .uses()
                            .shouldBeEmpty()
                    }
                }
            }
        },
    )
