package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.live.LiveMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun FakeSurrealServer.paramsOfLastLive(): JsonArray =
    received
        .last { it["method"]?.jsonPrimitive?.content == "live" }
        .getValue("params")
        .jsonArray

class LiveModeTest :
    ShouldSpec({
        context("Session.live") {
            should("send diff = false by default, so the mode is never left to the server to decide") {
                withServer { server, _, db ->
                    db.live(Table("book"))
                    awaiting { server.issued.size == 1 }

                    server.paramsOfLastLive() shouldBe
                        JsonArray(listOf(JsonPrimitive("book"), JsonPrimitive(false)))
                }
            }

            should("send diff = true for LiveMode.Diffs, which is what makes notifications carry patches") {
                withServer { server, _, db ->
                    db.live(Table("book"), LiveMode.Diffs)
                    awaiting { server.issued.size == 1 }

                    server.paramsOfLastLive() shouldBe
                        JsonArray(listOf(JsonPrimitive("book"), JsonPrimitive(true)))
                }
            }

            should("keep the mode across a reconnect, because the server forgets the subscription with the session") {
                withServer { server, _, db ->
                    db.live(Table("book"), LiveMode.Diffs)
                    awaiting { server.issued.size == 1 }

                    server.dropConnection()

                    awaiting { server.issued.size == 2 }
                    server.paramsOfLastLive() shouldBe
                        JsonArray(listOf(JsonPrimitive("book"), JsonPrimitive(true)))
                }
            }
        }
    })
