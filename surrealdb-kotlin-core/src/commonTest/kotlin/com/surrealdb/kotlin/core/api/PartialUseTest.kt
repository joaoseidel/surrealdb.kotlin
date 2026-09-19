package com.surrealdb.kotlin.core.api

import com.surrealdb.kotlin.core.api.error.SurrealProtocolException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
private fun jwt(payloadJson: String): String {
    fun encode(text: String) = Base64.UrlSafe.encode(text.encodeToByteArray()).trimEnd('=')
    return "${encode("""{"alg":"HS512","typ":"JWT"}""")}.${encode(payloadJson)}.${encode("signature")}"
}

private val RECORD_TOKEN =
    jwt("""{"NS":"probe17","DB":"probe17","AC":"p17_acct","ID":"p17_person:5tu4sl8ip0vf0gzgx5pb","exp":4102444800}""")
private val NAMESPACE_TOKEN = jwt("""{"NS":"probe17","ID":"p17_ns","exp":4102444800}""")
private val ROOT_TOKEN = jwt("""{"ID":"root","exp":4102444800}""")
private val LOWER_CASE_TOKEN = jwt("""{"ns":"lower","db":"case","exp":4102444800}""")

private const val AUTH_REQUIRED =
    """{"id":"1","error":{"code":-32000,"message":"authentication required",""" +
        """"kind":"NotAllowed","details":{"kind":"Auth","details":{"kind":"InvalidAuth"}}}}"""

private fun usePair(
    namespace: String?,
    database: String?,
): String {
    fun quote(value: String?) = if (value == null) "null" else "\"$value\""
    return """{"id":"1","result":{"database":${quote(database)},"namespace":${quote(namespace)}}}"""
}

private fun infoForRoot(
    namespace: String?,
    database: String?,
): String {
    fun quote(value: String?) = if (value == null) "null" else "\"$value\""
    return """{"id":"1","result":[{"status":"OK","time":"1ms","result":{"accesses":{},""" +
        """"defaults":{"database":${quote(database)},"namespace":${quote(namespace)}},"namespaces":{}}}]}"""
}

private class Sent(
    val method: String,
    val params: JsonArray,
    val headers: Map<String, String>,
) {
    fun param(index: Int): JsonElement = params[index]
}

private class Harness(
    autoAuthenticate: Boolean = false,
    private val reply: (Sent) -> String,
) {
    val sent = mutableListOf<Sent>()
    private var rejectNextUse = false

    private fun MockRequestHandleScope.json(content: String): HttpResponseData =
        respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    val client =
        Surreal(
            Surreal.Config(
                url = "http://localhost:8000",
                autoConnect = false,
                autoAuthenticate = autoAuthenticate,
                credentialProvider = if (autoAuthenticate) ({ Credentials.RootUser("root", "root") }) else null,
                httpClientFactory = { _ ->
                    HttpClient(
                        MockEngine { request ->
                            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                            val parsed = Json.parseToJsonElement(body).jsonObject
                            val call =
                                Sent(
                                    method = parsed["method"]!!.jsonPrimitive.content,
                                    params = parsed["params"]?.jsonArray ?: JsonArray(emptyList()),
                                    headers = request.headers.names().associateWith { request.headers[it]!! },
                                )
                            sent += call
                            when {
                                call.method == "use" && rejectNextUse -> {
                                    rejectNextUse = false
                                    json(AUTH_REQUIRED)
                                }

                                else -> {
                                    json(reply(call))
                                }
                            }
                        },
                    )
                },
            ),
        )

    fun rejectNextUse() {
        rejectNextUse = true
    }

    fun uses(): List<Sent> = sent.filter { it.method == "use" }
}

private fun standardReplies(defaults: Pair<String?, String?> = "main" to "main"): (Sent) -> String =
    { call ->
        when (call.method) {
            "signin" -> """{"id":"1","result":"${ROOT_TOKEN}"}"""
            "use" -> usePair(call.param(0).asStringOrNull(), call.param(1).asStringOrNull())
            "query" -> infoForRoot(defaults.first, defaults.second)
            else -> """{"id":"1","result":null}"""
        }
    }

private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

class PartialUseTest :
    ShouldSpec(
        {
            context("Session.use(Namespace)") {
                should(
                    "send use with the namespace and a JSON null, and leave the snapshot with that namespace and no database, because SurrealDB clears the database for [ns, null]",
                ) {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()

                        db.use(Namespace("probe17"))

                        val use = h.uses().single()
                        use.param(0) shouldBe JsonPrimitive("probe17")
                        use.param(1).shouldBeInstanceOf<JsonNull>()
                        db.namespace() shouldBe "probe17"
                        db.database().shouldBeNull()
                    }
                }

                should(
                    "clear a database that a two-argument use had selected, so the snapshot never names a database of another namespace",
                ) {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()
                        db.use(Namespace("probe17"), Database("probe17"))

                        db.use(Namespace("probe17_other"))

                        db.namespace() shouldBe "probe17_other"
                        db.database().shouldBeNull()
                    }
                }
            }

            context("Session.use(Database)") {
                should(
                    "send use with the namespace already on the session and the new database, and keep namespace() as it was",
                ) {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()
                        db.use(Namespace("probe17"))

                        db.use(Database("probe17_other"))

                        val use = h.uses().last()
                        use.param(0) shouldBe JsonPrimitive("probe17")
                        use.param(1) shouldBe JsonPrimitive("probe17_other")
                        db.namespace() shouldBe "probe17"
                        db.database() shouldBe "probe17_other"
                    }
                }

                should(
                    "throw IllegalStateException before any request when the session has no namespace, because the server clears the namespace before refusing [null, db]",
                ) {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()

                        val thrown = shouldThrow<IllegalStateException> { db.use(Database("probe17")) }

                        thrown.message shouldContain "use(Namespace)"
                        h.sent.shouldBeEmpty()
                        db.namespace().shouldBeNull()
                    }
                }
            }

            context("Session.useDefaults()") {
                should(
                    "send use with the NS and DB claims of the token held after authenticate, and fill the snapshot from them",
                ) {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()
                        db.authenticate(RECORD_TOKEN)

                        db.useDefaults()

                        val use = h.uses().single()
                        use.param(0) shouldBe JsonPrimitive("probe17")
                        use.param(1) shouldBe JsonPrimitive("probe17")
                        db.namespace() shouldBe "probe17"
                        db.database() shouldBe "probe17"
                        h.sent.none { it.method == "query" } shouldBe true
                    }
                }

                should("read lower-case ns and db claims too, for an access method of type JWT") {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()
                        db.authenticate(LOWER_CASE_TOKEN)

                        db.useDefaults()

                        db.namespace() shouldBe "lower"
                        db.database() shouldBe "case"
                    }
                }

                should("send use [NS, null] for a token with a namespace claim and no database claim") {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()
                        db.authenticate(NAMESPACE_TOKEN)

                        db.useDefaults()

                        val use = h.uses().single()
                        use.param(0) shouldBe JsonPrimitive("probe17")
                        use.param(1).shouldBeInstanceOf<JsonNull>()
                        db.namespace() shouldBe "probe17"
                        db.database().shouldBeNull()
                    }
                }

                should(
                    "send nothing and change nothing when a namespace is already selected, because the server's (NONE, NONE) is a no-op then",
                ) {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()
                        db.authenticate(RECORD_TOKEN)
                        db.use(Namespace("elsewhere"), Database("elsewhere"))
                        val before = h.sent.size

                        db.useDefaults()

                        h.sent shouldHaveSize before
                        db.namespace() shouldBe "elsewhere"
                        db.database() shouldBe "elsewhere"
                    }
                }

                should("read defaults from INFO FOR ROOT for a token with no namespace claim, and use that pair") {
                    runTest {
                        val h = Harness(reply = standardReplies("main" to "main"))
                        val db = h.client.session()
                        db.signin(Credentials.RootUser("root", "root"))

                        db.useDefaults()

                        h.sent.single { it.method == "query" }.param(0) shouldBe JsonPrimitive("INFO FOR ROOT")
                        val use = h.uses().single()
                        use.param(0) shouldBe JsonPrimitive("main")
                        use.param(1) shouldBe JsonPrimitive("main")
                        db.namespace() shouldBe "main"
                        db.database() shouldBe "main"
                    }
                }

                should("send nothing when INFO FOR ROOT reports null defaults") {
                    runTest {
                        val h = Harness(reply = standardReplies(null to null))
                        val db = h.client.session()
                        db.signin(Credentials.RootUser("root", "root"))

                        db.useDefaults()

                        h.uses().shouldBeEmpty()
                        db.namespace().shouldBeNull()
                        db.database().shouldBeNull()
                    }
                }

                should("throw IllegalStateException before any request when the session holds no token") {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()

                        val thrown = shouldThrow<IllegalStateException> { db.useDefaults() }

                        thrown.message shouldContain "sign in or authenticate first"
                        h.sent.shouldBeEmpty()
                    }
                }

                should(
                    "sign in through the credential provider after the server refuses the use, and succeed on the retry",
                ) {
                    runTest {
                        val h = Harness(autoAuthenticate = true, reply = standardReplies())
                        val db = h.client.session()
                        db.authenticate(RECORD_TOKEN)
                        h.rejectNextUse()

                        db.useDefaults()

                        h.sent.map { it.method } shouldBe listOf("authenticate", "use", "signin", "use")
                        db.namespace() shouldBe "probe17"
                    }
                }
            }

            context("over HTTP") {
                should(
                    "send only Surreal-NS on the next request after use(Namespace), and both headers after use(Database)",
                ) {
                    runTest {
                        val h = Harness(reply = standardReplies())
                        val db = h.client.session()

                        db.use(Namespace("probe17"))
                        db.ping()
                        val afterNamespace = h.sent.last()
                        db.use(Database("probe17_other"))
                        db.ping()
                        val afterDatabase = h.sent.last()

                        afterNamespace.headers["Surreal-NS"] shouldBe "probe17"
                        afterNamespace.headers["Surreal-DB"].shouldBeNull()
                        afterDatabase.headers["Surreal-NS"] shouldBe "probe17"
                        afterDatabase.headers["Surreal-DB"] shouldBe "probe17_other"
                    }
                }
            }

            context("Session.version") {
                should("throw SurrealProtocolException when the server answers null, rather than an empty string") {
                    runTest {
                        val h = Harness(reply = { """{"id":"1","result":null}""" })
                        val db = h.client.session()

                        shouldThrow<SurrealProtocolException> { db.version() }
                    }
                }
            }
        },
    )
