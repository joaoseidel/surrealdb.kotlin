package com.surrealdb.kotlin.core.runtime.codec

import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.error.SurrealProtocolException
import com.surrealdb.kotlin.core.runtime.RpcRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val codec = Codec(Surreal.Config(url = "http://localhost:8000"))

/** Parses back through Json so assertions are about structure, not string formatting. */
private fun parse(text: String) =
    Surreal
        .Config(url = "x")
        .json
        .parseToJsonElement(text)
        .jsonObject

class CodecTest :
    ShouldSpec(
        {
            context("Codec HTTP") {
                should("encode the id, method and params the server dispatches on") {
                    val request =
                        RpcRequest(
                            id = "abc-123",
                            method = "query",
                            params =
                                listOf(
                                    JsonPrimitive("SELECT * FROM person"),
                                    buildJsonObject { put("limit", JsonPrimitive(10)) },
                                ),
                        )

                    val parsed = parse(codec.encodeHttpPayload(request).decodeToString())

                    parsed["id"]?.jsonPrimitive?.content shouldBe "abc-123"
                    parsed["method"]?.jsonPrimitive?.content shouldBe "query"
                    parsed["params"].shouldNotBeNull()
                }

                should("decode a successful response, leaving error unset") {
                    val response = codec.decodeHttpPayload("""{"id":"1","result":{"ok":true}}""".encodeToByteArray())

                    response.id shouldBe "1"
                    (response.result as JsonObject)["ok"]?.jsonPrimitive?.content?.toBooleanStrict() shouldBe true
                    response.error.shouldBeNull()
                }

                should("decode an error response, keeping the server's code and message") {
                    val payload = """{"id":"1","error":{"code":-32000,"message":"oops"}}""".encodeToByteArray()

                    val response = codec.decodeHttpPayload(payload)

                    response.id shouldBe "1"
                    response.error.shouldNotBeNull()
                    response.error?.code shouldBe -32000
                    response.error?.message shouldBe "oops"
                }

                should("fail as a protocol error on a malformed payload, not a serialization one") {
                    shouldThrow<SurrealProtocolException> {
                        codec.decodeHttpPayload("not json at all".encodeToByteArray())
                    }
                }

                should("announce application/json, which the server requires") {
                    codec.contentTypeHeader() shouldBe "application/json"
                }
            }

            context("Codec WebSocket") {
                should("encode a request as JSON the server can dispatch on") {
                    val request = RpcRequest(id = "ws-1", method = "ping", params = emptyList())

                    val parsed = parse(codec.encodeWsText(request))

                    parsed["id"]?.jsonPrimitive?.content shouldBe "ws-1"
                    parsed["method"]?.jsonPrimitive?.content shouldBe "ping"
                }

                should("leave id unset on a live notification, which is how it is told from a reply") {
                    val frame = """{"result":{"action":"CREATE","id":"live-1","result":{"id":"person:1"}}}"""

                    val response = codec.decodeWsText(frame)

                    response.id.shouldBeNull()
                    response.result.shouldNotBeNull()
                }

                should("fail as a protocol error on malformed text, not a serialization one") {
                    shouldThrow<SurrealProtocolException> { codec.decodeWsText("definitely not json") }
                }
            }
        },
    )
