package com.surrealdb.kotlin

import com.surrealdb.kotlin.error.SurrealAuthenticationException
import com.surrealdb.kotlin.internal.parseLiveNotification
import com.surrealdb.kotlin.model.CborRpcResponse
import com.surrealdb.kotlin.model.CborValue
import com.surrealdb.kotlin.model.SurrealRpcResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SurrealClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `rpc returns json result`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"id":"1","result":{"ok":true}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = testClient(engine = engine)
        val result = client.ping().jsonObject

        assertEquals(true, result["ok"]?.jsonPrimitive?.content?.toBooleanStrict())
    }

    @Test
    fun `rpc supports cbor codec`() = runTest {
        val cbor = SurrealClientConfig(
            httpEndpoint = "http://localhost:8000",
            codec = SurrealHttpCodec.CBOR,
        ).cbor
        val config = SurrealClientConfig(
            httpEndpoint = "http://localhost:8000",
            codec = SurrealHttpCodec.CBOR,
            cbor = cbor,
            httpClientFactory = { _ ->
                HttpClient(MockEngine) {
                    engine {
                        addHandler { _ ->
                            val payload = cbor.encodeToByteArray(
                                CborRpcResponse.serializer(),
                                CborRpcResponse(id = "1", result = CborValue.StringValue("ok")),
                            )
                            respond(
                                content = ByteReadChannel(payload),
                                status = HttpStatusCode.OK,
                                headers = headersOf(
                                    HttpHeaders.ContentType,
                                    "application/cbor",
                                ),
                            )
                        }
                    }
                }
            },
        )

        val client = SurrealClient(config)
        val result = client.ping()
        assertEquals("ok", result.jsonPrimitive.content)
    }

    @Test
    fun `maps auth errors and retries with credential provider`() = runTest {
        var queryCalls = 0
        val engine = MockEngine { request ->
            when (request.methodName(json)) {
                "query" -> {
                    queryCalls += 1
                    if (queryCalls == 1) {
                        respond(
                            content = """{"id":"1","error":{"code":-32000,"message":"authentication required"}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond(
                            content = """{"id":"1","result":[{"status":"OK","result":[{"id":"person:1"}]}]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                }

                "signin" -> respond(
                    content = """{"id":"1","result":"jwt.token"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

                else -> error("unexpected method")
            }
        }

        val client = SurrealClient(
            SurrealClientConfig(
                httpEndpoint = "http://localhost:8000",
                autoAuthenticate = true,
                credentialProvider = {
                    SurrealAuthInput.SignIn(
                        buildJsonObject {
                            put("user", JsonPrimitive("root"))
                            put("pass", JsonPrimitive("root"))
                        },
                    )
                },
                httpClientFactory = { _ -> HttpClient(engine) },
            ),
        )

        val result = client.query("SELECT * FROM person")
        val status = result.jsonArray[0].jsonObject["status"]?.jsonPrimitive?.content
        assertEquals("OK", status)
    }

    @Test
    fun `throws auth error when auto mode disabled`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"id":"1","error":{"code":-32000,"message":"authentication required"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = testClient(engine = engine)

        assertFailsWith<SurrealAuthenticationException> {
            client.query("SELECT * FROM person")
        }
    }

    @Test
    fun `typed decode helper works`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"id":"1","result":{"id":"person:1","name":"Ada"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = testClient(engine = engine)
        val person: Person = client.selectAs("person:1")

        assertEquals("Ada", person.name)
    }

    @Test
    fun `parses live notification payload`() {
        val response = json.decodeFromString(
            SurrealRpcResponse.serializer(),
            """{"result":{"action":"CREATE","id":"live-1","result":{"id":"person:1"}}}""",
        )

        val notification = parseLiveNotification(response)
        assertNotNull(notification)
        assertEquals("CREATE", notification.action)
        assertEquals("live-1", notification.liveQueryId)
    }

    private fun testClient(engine: MockEngine): SurrealClient {
        return SurrealClient(
            SurrealClientConfig(
                httpEndpoint = "http://localhost:8000",
                httpClientFactory = { _ -> HttpClient(engine) },
            ),
        )
    }

    private suspend fun HttpRequestData.methodName(json: Json): String {
        val bodyBytes = when (val requestBody = body) {
            is io.ktor.http.content.OutgoingContent.ByteArrayContent -> requestBody.bytes()
            else -> error("Unsupported request body: ${requestBody::class.simpleName}")
        }
        val payload = json.decodeFromString<JsonObject>(bodyBytes.decodeToString())
        return payload["method"]?.jsonPrimitive?.content ?: error("Missing method")
    }

    @Serializable
    private data class Person(
        val id: String,
        val name: String,
    )
}
