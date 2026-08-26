package com.surrealdb.kotlin.core.api

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest

private fun tokenRespondingClient(): Surreal {
    val engine =
        MockEngine { _ ->
            respond(
                content = """{"id":"1","result":"jwt-token"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    return Surreal(
        Surreal.Config(
            url = "http://localhost:8000",
            autoConnect = false,
            httpClientFactory = { _ -> HttpClient(engine) },
        ),
    )
}

private fun credentials(user: String) = Credentials.RootUser(user, "pass")

class SessionLifecycleTest :
    ShouldSpec(
        {
            context("Surreal.session") {
                should("give every session its own id, so state cannot be shared by accident") {
                    runTest {
                        val client = tokenRespondingClient()

                        val ids = List(3) { client.session().sessionId }

                        ids.toSet().size shouldBe 3
                    }
                }

                should("hand back a session the client does not itself impersonate") {
                    runTest {
                        val client = tokenRespondingClient()
                        val first = client.session()

                        val second = client.session()

                        first.signin(credentials("u"))

                        first.accessToken().shouldNotBeNull()
                        second.accessToken().shouldBeNull()
                    }
                }
            }

            context("state is per session") {
                should("keep an access token on the session that signed in") {
                    runTest {
                        val client = tokenRespondingClient()
                        val one = client.session()
                        val other = client.session()

                        one.signin(credentials("u"))

                        one.accessToken().shouldNotBeNull()
                        other.accessToken().shouldBeNull()
                    }
                }

                should("keep namespace and database on the session that selected them") {
                    runTest {
                        val client = tokenRespondingClient()
                        val one = client.session()
                        val other = client.session()

                        one.use(Namespace("ns1"), Database("db1"))

                        one.namespace() shouldBe "ns1"
                        one.database() shouldBe "db1"
                        other.namespace().shouldBeNull()
                        other.database().shouldBeNull()
                    }
                }

                should("clear only the invalidating session's token") {
                    runTest {
                        val client = tokenRespondingClient()
                        val one = client.session()
                        val other = client.session()
                        one.signin(credentials("u"))
                        other.signin(credentials("v"))

                        one.invalidate()

                        one.accessToken().shouldBeNull()
                        other.accessToken().shouldNotBeNull()
                    }
                }

                should("clear only the resetting session's token, namespace and database") {
                    runTest {
                        val client = tokenRespondingClient()
                        val one = client.session()
                        val other = client.session()
                        one.signin(credentials("u"))
                        one.use(Namespace("ns1"), Database("db1"))
                        other.signin(credentials("v"))
                        other.use(Namespace("ns2"), Database("db2"))

                        one.reset()

                        one.accessToken().shouldBeNull()
                        one.namespace().shouldBeNull()
                        one.database().shouldBeNull()
                        other.accessToken().shouldNotBeNull()
                        other.namespace() shouldBe "ns2"
                    }
                }
            }

            context("Surreal.closeSession") {
                should("leave every other session working, since closing is best effort") {
                    runTest {
                        val client = tokenRespondingClient()
                        val survivor = client.session()
                        val closed = client.session()

                        client.closeSession(closed)

                        survivor.signin(credentials("u"))
                        survivor.accessToken().shouldNotBeNull()
                    }
                }
            }
        },
    )
