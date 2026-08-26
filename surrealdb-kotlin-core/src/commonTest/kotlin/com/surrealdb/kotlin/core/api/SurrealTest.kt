package com.surrealdb.kotlin.core.api

import com.surrealdb.kotlin.core.api.error.SurrealAuthenticationException
import com.surrealdb.kotlin.core.api.error.SurrealFeatureNotSupportedException
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.runtime.RpcResponse
import com.surrealdb.kotlin.core.runtime.codec.parseLiveNotification
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SurrealTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    @Test
    fun `rpc returns json result`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"id":"1","result":{"ok":true}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

            val client = testClient(engine = engine)
            val result = client.session().ping().jsonObject

            assertEquals(true, result["ok"]?.jsonPrimitive?.content?.toBooleanStrict())
        }

    @Test
    fun `maps auth errors and retries with credential provider`() =
        runTest {
            var queryCalls = 0
            val engine =
                MockEngine { request ->
                    when (request.methodName(json)) {
                        "query" -> {
                            queryCalls += 1
                            if (queryCalls == 1) {
                                respond(
                                    content =
                                        """{"id":"1","error":{"code":-32000,"message":"authentication required",""" +
                                            """"kind":"NotAllowed","details":{"kind":"Auth","details":{"kind":"InvalidAuth"}}}}""",
                                    status = HttpStatusCode.OK,
                                    headers =
                                        headersOf(
                                            HttpHeaders.ContentType,
                                            ContentType.Application.Json.toString(),
                                        ),
                                )
                            } else {
                                respond(
                                    content = """{"id":"1","result":[{"status":"OK","result":[{"id":"person:1"}]}]}""",
                                    status = HttpStatusCode.OK,
                                    headers =
                                        headersOf(
                                            HttpHeaders.ContentType,
                                            ContentType.Application.Json.toString(),
                                        ),
                                )
                            }
                        }

                        "signin" -> {
                            respond(
                                content = """{"id":"1","result":"jwt.token"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }

                        else -> {
                            error("unexpected method")
                        }
                    }
                }

            val client =
                Surreal(
                    Surreal.Config(
                        url = "http://localhost:8000",
                        autoAuthenticate = true,
                        credentialProvider = {
                            Credentials.RootUser("root", "root")
                        },
                        httpClientFactory = { _ -> HttpClient(engine) },
                    ),
                )

            val result = client.session().query(BoundQuery().appendLiteral("SELECT * FROM person"))
            assertEquals(1, result.size)
            assertEquals("person:1", result[0].content["id"]?.jsonPrimitive?.content)
        }

    @Test
    fun `throws auth error when auto mode disabled`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """{"id":"1","error":{"code":-32000,"message":"authentication required",""" +
                                """"kind":"NotAllowed","details":{"kind":"Auth","details":{"kind":"InvalidAuth"}}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

            val client = testClient(engine = engine)

            assertFailsWith<SurrealAuthenticationException> {
                client.session().query(BoundQuery().appendLiteral("SELECT * FROM person"))
            }
        }

    @Test
    fun `parses live notification payload`() {
        val response =
            json.decodeFromString(
                RpcResponse.serializer(),
                """{"result":{"action":"CREATE","id":"live-1","result":{"id":"person:1"}}}""",
            )

        val notification = parseLiveNotification(response)
        assertNotNull(notification)
        assertEquals("CREATE", notification.action)
        assertEquals("live-1", notification.liveQueryId)
    }

    @Test
    fun `http engine reports its feature set and rejects live queries`() =
        runTest {
            val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
            val client = testClient(engine = engine)

            assertTrue(Feature.ExportImport in client.features)
            assertTrue(Feature.LiveQueries !in client.features)
            assertFailsWith<SurrealFeatureNotSupportedException> {
                client.session().live("person")
            }
            assertFailsWith<SurrealFeatureNotSupportedException> {
                client.session().liveEvents<JsonElement>("DELETE person")
            }
        }

    @Test
    fun `session returns isolated sessions sharing the connection`() =
        runTest {
            val seenAuth = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    seenAuth += request.headers[HttpHeaders.Authorization]
                    respond(
                        content = """{"id":"1","result":"jwt-token-1"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

            val client = testClient(engine = engine)
            val sessionA = client.session()
            val sessionB = client.session()

            sessionA.signin(Credentials.RootUser("a", "pass"))
            assertEquals(null, sessionB.accessToken())
            assertEquals("jwt-token-1", sessionA.accessToken())

            sessionB.ping()
            assertEquals(null, seenAuth.last())
        }

    private fun testClient(engine: MockEngine): Surreal =
        Surreal(
            Surreal.Config(
                url = "http://localhost:8000",
                autoConnect = false,
                httpClientFactory = { _ -> HttpClient(engine) },
            ),
        )

    private suspend fun HttpRequestData.methodName(json: Json): String {
        val bodyBytes =
            when (val requestBody = body) {
                is io.ktor.http.content.OutgoingContent.ByteArrayContent -> requestBody.bytes()
                else -> error("Unsupported request body: ${requestBody::class.simpleName}")
            }
        val payload = json.decodeFromString<JsonObject>(bodyBytes.decodeToString())
        return payload["method"]?.jsonPrimitive?.content ?: error("Missing method")
    }
}
