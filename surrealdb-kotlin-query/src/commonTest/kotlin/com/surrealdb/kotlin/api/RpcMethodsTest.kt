package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.query.People
import com.surrealdb.kotlin.api.query.ReturnMode
import com.surrealdb.kotlin.api.query.create
import com.surrealdb.kotlin.api.query.delete
import com.surrealdb.kotlin.api.query.insert
import com.surrealdb.kotlin.api.query.insertRelation
import com.surrealdb.kotlin.api.query.merge
import com.surrealdb.kotlin.api.query.patch
import com.surrealdb.kotlin.api.query.relate
import com.surrealdb.kotlin.api.query.run
import com.surrealdb.kotlin.api.query.select
import com.surrealdb.kotlin.api.query.surqlTemplate
import com.surrealdb.kotlin.api.query.update
import com.surrealdb.kotlin.api.query.upsert
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val harnessJson = Json { ignoreUnknownKeys = true }

private suspend fun readBody(request: HttpRequestData): String =
    when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> error("unsupported body type: ${body::class.simpleName}")
    }

private class RpcHarness {
    var lastMethod: String? = null
    var lastParams: JsonArray? = null
    var stubResult: String = """{"id":"1","result":null}"""

    val engine =
        MockEngine { request ->
            val parsed = harnessJson.parseToJsonElement(readBody(request)).jsonObject
            lastMethod = parsed["method"]?.jsonPrimitive?.content
            lastParams = parsed["params"]?.jsonArray
            respond(
                content = stubResult,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

    val client =
        Surreal(
            Surreal.Config(
                url = "http://localhost:8000",
                autoConnect = false,
                httpClientFactory = { _ -> HttpClient(engine) },
            ),
        )

    lateinit var db: Session
        private set

    suspend fun open(): RpcHarness = apply { db = client.session() }

    fun lastSurql(): String = lastParams?.get(0)?.jsonPrimitive?.content ?: error("no SurrealQL in last params")

    fun paramCount(): Int = lastParams?.size ?: 0

    fun param(index: Int) = lastParams?.get(index)
}

private fun queryEnvelope(stub: String = "null") =
    """{"id":"1","result":[{"status":"OK","time":"1ms","result":$stub}]}"""

private suspend fun harness() = RpcHarness().open()

private suspend fun builderHarness() = RpcHarness().apply { stubResult = queryEnvelope() }.open()

/**
 * Wire-format specs for every public RPC method: what method name and params reach the transport.
 *
 * Builder-style CRUD all compiles down to the `query` RPC, so those cases assert the SurrealQL
 * fragment and its bindings rather than a dedicated RPC name.
 */
class RpcMethodsTest :
    ShouldSpec({
        context("server methods") {
            should("send ping with no params") {
                runTest {
                    val h = harness()

                    h.db.ping()

                    h.lastMethod shouldBe "ping"
                    h.paramCount() shouldBe 0
                }
            }

            should("send version with no params") {
                runTest {
                    val h = harness()

                    h.db.version()

                    h.lastMethod shouldBe "version"
                    h.paramCount() shouldBe 0
                }
            }

            should("send namespace and database as two params, not one pair") {
                runTest {
                    val h = harness()

                    h.db.use(Namespace("ns1"), Database("db1"))

                    h.lastMethod shouldBe "use"
                    h.paramCount() shouldBe 2
                    h.param(0)?.jsonPrimitive?.content shouldBe "ns1"
                    h.param(1)?.jsonPrimitive?.content shouldBe "db1"
                }
            }

            should("answer who-am-I through query, because there is no auth RPC") {
                runTest {
                    val h = harness()
                    h.stubResult = """{"id":"1","result":[{"status":"OK","result":{"id":"u:1"}}]}"""

                    h.db.whoami()

                    h.lastMethod shouldBe "query"
                    h.lastSurql() shouldBe "SELECT * FROM ONLY \$auth"
                }
            }
        }

        context("auth methods") {
            should("send signup with the credentials object intact") {
                runTest {
                    val h = harness()

                    h.db.signup(Credentials.Raw(buildJsonObject { put("user", JsonPrimitive("u")) }))

                    h.lastMethod shouldBe "signup"
                    h
                        .param(0)
                        ?.jsonObject
                        ?.get("user")
                        ?.jsonPrimitive
                        ?.content shouldBe "u"
                }
            }

            should("send signin with the credentials object intact") {
                runTest {
                    val h = harness()

                    h.db.signin(Credentials.Raw(buildJsonObject { put("user", JsonPrimitive("u")) }))

                    h.lastMethod shouldBe "signin"
                    h
                        .param(0)
                        ?.jsonObject
                        ?.get("user")
                        ?.jsonPrimitive
                        ?.content shouldBe "u"
                }
            }

            should("send authenticate with the raw token string") {
                runTest {
                    val h = harness()

                    h.db.authenticate("jwt-here")

                    h.lastMethod shouldBe "authenticate"
                    h.param(0)?.jsonPrimitive?.content shouldBe "jwt-here"
                }
            }

            should("send invalidate with no params") {
                runTest {
                    val h = harness()

                    h.db.invalidate()

                    h.lastMethod shouldBe "invalidate"
                    h.paramCount() shouldBe 0
                }
            }

            should("send reset with no params") {
                runTest {
                    val h = harness()

                    h.db.reset()

                    h.lastMethod shouldBe "reset"
                    h.paramCount() shouldBe 0
                }
            }
        }

        context("session variables") {
            should("send let with key and value as separate params") {
                runTest {
                    val h = harness()

                    h.db.`let`("k", JsonPrimitive("v"))

                    h.lastMethod shouldBe "let"
                    h.param(0)?.jsonPrimitive?.content shouldBe "k"
                    h.param(1)?.jsonPrimitive?.content shouldBe "v"
                }
            }

            should("encode the value by its Kotlin type, so a variable is bound without assembling JSON") {
                runTest {
                    val h = harness()

                    h.db.`let`("k", 18)

                    h.param(1)?.jsonPrimitive?.content shouldBe "18"
                    h.param(1)?.jsonPrimitive?.isString shouldBe false
                }
            }

            should("send unset with the key alone") {
                runTest {
                    val h = harness()

                    h.db.unset("k")

                    h.lastMethod shouldBe "unset"
                    h.paramCount() shouldBe 1
                    h.param(0)?.jsonPrimitive?.content shouldBe "k"
                }
            }
        }

        context("query") {
            should("omit the vars param when the caller passed none") {
                runTest {
                    val h = harness()

                    h.db.query(surqlTemplate { "SELECT 1" })

                    h.lastMethod shouldBe "query"
                    h.paramCount() shouldBe 1
                    h.param(0)?.jsonPrimitive?.content shouldBe "SELECT 1"
                }
            }

            should("send bindings as a second param, so they are never inlined into the SQL") {
                runTest {
                    val h = harness()

                    h.db.query(surqlTemplate { "SELECT type::table(${bind("person")})" })

                    h.lastMethod shouldBe "query"
                    h.paramCount() shouldBe 2
                    h
                        .param(1)
                        ?.jsonObject
                        ?.get("_0")
                        ?.jsonPrimitive
                        ?.content shouldBe "person"
                }
            }
        }

        context("builder CRUD compiles to the query RPC") {
            should("bind the table name rather than inline it, so it cannot be injected") {
                runTest {
                    val h = builderHarness()

                    h.db.select(Table("person")).await()

                    h.lastMethod shouldBe "query"
                    h.lastSurql() shouldStartWith "SELECT * FROM type::table("
                    h
                        .param(1)
                        ?.jsonObject
                        ?.values
                        ?.firstOrNull()
                        ?.jsonPrimitive
                        ?.content shouldBe "person"
                }
            }

            should("render a where clause into the statement") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .select(People)
                        .where { age eq 30 }
                        .await()

                    h.lastSurql() shouldContain "WHERE"
                    h.lastSurql() shouldContain "age"
                }
            }

            should("compile create to CREATE ONLY with a bound CONTENT") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .create(RecordId("person", "1"))
                        .content(buildJsonObject { put("name", JsonPrimitive("Ada")) })
                        .await()

                    h.lastSurql() shouldStartWith "CREATE ONLY type::record("
                    h.lastSurql() shouldContain " CONTENT "
                }
            }

            should("compile update to UPDATE ONLY") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .update(RecordId("person", "1"))
                        .content(buildJsonObject { put("name", JsonPrimitive("New")) })
                        .await()

                    h.lastSurql() shouldStartWith "UPDATE ONLY type::record("
                }
            }

            should("compile upsert to UPSERT ONLY") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .upsert(RecordId("person", "1"))
                        .content(buildJsonObject { put("name", JsonPrimitive("X")) })
                        .await()

                    h.lastSurql() shouldStartWith "UPSERT ONLY type::record("
                }
            }

            should("compile merge to UPDATE ONLY MERGE, not a dedicated merge RPC") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .merge(RecordId("person", "1"), buildJsonObject { put("active", JsonPrimitive(true)) })
                        .await()

                    h.lastSurql() shouldStartWith "UPDATE ONLY type::record("
                    h.lastSurql() shouldContain " MERGE "
                }
            }

            should("compile patch to UPDATE ONLY PATCH") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .patch(RecordId("person", "1"), buildJsonObject { put("op", JsonPrimitive("replace")) })
                        .await()

                    h.lastSurql() shouldStartWith "UPDATE ONLY type::record("
                    h.lastSurql() shouldContain " PATCH "
                }
            }

            should("append the RETURN clause a patch's returnMode names") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .patch(RecordId("person", "1"), buildJsonObject {})
                        .returnMode(ReturnMode.Diff)
                        .await()

                    h.lastSurql() shouldEndWith " RETURN DIFF"
                }
            }

            should("compile delete to DELETE ONLY") {
                runTest {
                    val h = builderHarness()

                    h.db.delete(RecordId("person", "1")).await()

                    h.lastSurql() shouldStartWith "DELETE ONLY type::record("
                }
            }
        }

        context("graph") {
            should("compile relate to a RELATE arrow chain") {
                runTest {
                    val h = builderHarness()

                    h.db.relate(RecordId("person", "a"), Table("likes"), RecordId("person", "b")).await()

                    h.lastSurql() shouldStartWith "RELATE "
                    h.lastSurql() shouldContain "->"
                }
            }

            should("compile insert to INSERT INTO with the data bound") {
                runTest {
                    val h = builderHarness()

                    h.db.insert(Table("person"), buildJsonObject { put("name", JsonPrimitive("A")) }).await()

                    h.lastSurql() shouldStartWith "INSERT INTO $"
                }
            }

            should("compile insertRelation to INSERT RELATION INTO") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .insertRelation(Table("likes"), buildJsonObject { put("in", JsonPrimitive("p:a")) })
                        .await()

                    h.lastSurql() shouldStartWith "INSERT RELATION INTO $"
                }
            }

            should("compile run to a function call with bound args") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .run("fn::greet")
                        .args("world")
                        .await()

                    h.lastMethod shouldBe "query"
                    h.lastSurql() shouldStartWith "fn::greet("
                }
            }

            should("emit the angle-bracket form when a function version is named") {
                runTest {
                    val h = builderHarness()

                    h.db
                        .run("fn::greet")
                        .version("1.0")
                        .args("world")
                        .await()

                    h.lastSurql() shouldStartWith "fn::greet<1.0>("
                }
            }
        }

        context("result propagation") {
            should("hand back JsonNull rather than null, so callers need no null check") {
                runTest {
                    val h = harness()
                    h.stubResult = """{"id":"1","result":null}"""

                    h.db.ping().shouldBeInstanceOf<JsonNull>()
                }
            }

            should("hand back a complex result verbatim, without reshaping it") {
                runTest {
                    val h = harness()
                    h.stubResult = """{"id":"1","result":[{"a":1},{"b":2}]}"""

                    h.db.ping().jsonArray shouldHaveSize 2
                }
            }

            should("tolerate a response with no id, which live-capable servers may send") {
                runTest {
                    val h = harness()
                    h.stubResult = """{"result":{"ok":true}}"""

                    h.db
                        .ping()
                        .jsonObject["ok"]
                        ?.jsonPrimitive
                        ?.content shouldBe "true"
                }
            }
        }
    })
