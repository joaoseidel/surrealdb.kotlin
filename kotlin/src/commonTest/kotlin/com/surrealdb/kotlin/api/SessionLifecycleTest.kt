package com.surrealdb.kotlin.api

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A client whose every call answers with a token, so these specs are about session state only. */
private fun client(): SurrealClient {
    val engine =
        MockEngine { _ ->
            respond(
                content = """{"id":"1","result":"jwt-token"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    return SurrealClient(
        SurrealClientConfig(
            url = "http://localhost:8000",
            autoConnect = false,
            httpClientFactory = { _ -> HttpClient(engine) },
        ),
    )
}

private fun credentials(user: String) = buildJsonObject { put("user", JsonPrimitive(user)) }

class SessionLifecycleTest :
    ShouldSpec({
        context("SurrealClient.newSession") {
            should("hand back an id distinct from the root, since the root is itself a session") {
                runTest {
                    val client = client()

                    val session = client.newSession()

                    session.sessionId shouldNotBe client.sessionId
                }
            }

            should("give every session its own id, so state cannot be shared by accident") {
                runTest {
                    val client = client()

                    val ids =
                        setOf(client.newSession(), client.newSession(), client.newSession())
                            .map { it.sessionId }

                    ids.toSet().size shouldBe 3
                }
            }
        }

        context("state is per session") {
            // The whole point of newSession: one connection, independent auth and context.
            should("keep an access token on the session that signed in") {
                runTest {
                    val client = client()
                    val other = client.newSession()

                    client.signin(credentials("u"))

                    client.accessToken().shouldNotBeNull()
                    other.accessToken().shouldBeNull()
                }
            }

            should("keep namespace and database on the session that selected them") {
                runTest {
                    val client = client()
                    val other = client.newSession()

                    client.use("ns1", "db1")

                    client.namespace() shouldBe "ns1"
                    client.database() shouldBe "db1"
                    other.namespace().shouldBeNull()
                    other.database().shouldBeNull()
                }
            }

            should("clear only the invalidating session's token") {
                runTest {
                    val client = client()
                    val other = client.newSession()
                    client.signin(credentials("u"))
                    other.signin(credentials("v"))

                    client.invalidate()

                    client.accessToken().shouldBeNull()
                    other.accessToken().shouldNotBeNull()
                }
            }

            should("clear only the resetting session's token, namespace and database") {
                runTest {
                    val client = client()
                    val other = client.newSession()
                    client.signin(credentials("u"))
                    client.use("ns1", "db1")
                    other.signin(credentials("v"))
                    other.use("ns2", "db2")

                    client.reset()

                    client.accessToken().shouldBeNull()
                    client.namespace().shouldBeNull()
                    client.database().shouldBeNull()
                    other.accessToken().shouldNotBeNull()
                    other.namespace() shouldBe "ns2"
                }
            }
        }

        context("SurrealClient.closeSession") {
            should("ignore an attempt to close the root, which must stay usable") {
                runTest {
                    val client = client()

                    client.closeSession(client)

                    client.signin(credentials("u"))
                    client.accessToken().shouldNotBeNull()
                }
            }

            should("close a child without disturbing the root, since closing is best effort") {
                runTest {
                    val client = client()
                    val child = client.newSession()

                    client.closeSession(child)

                    // The contract is only that closing does not throw and leaves the root working;
                    // operations on a closed session are deliberately unspecified.
                    client.signin(credentials("u"))
                    client.accessToken().shouldNotBeNull()
                }
            }
        }
    })
