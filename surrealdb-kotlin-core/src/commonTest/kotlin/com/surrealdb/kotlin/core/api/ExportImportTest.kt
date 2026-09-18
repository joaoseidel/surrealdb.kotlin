package com.surrealdb.kotlin.core.api

import com.surrealdb.kotlin.core.api.error.ErrorKind
import com.surrealdb.kotlin.core.api.error.SurrealAlreadyExistsException
import com.surrealdb.kotlin.core.api.error.SurrealAuthenticationException
import com.surrealdb.kotlin.core.api.error.SurrealFeatureNotSupportedException
import com.surrealdb.kotlin.core.api.error.SurrealRpcException
import com.surrealdb.kotlin.core.api.error.SurrealTransportException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val EXPORT_TEXT =
    "-- ------------------------------\n-- OPTION\n-- ------------------------------\n\nOPTION IMPORT;\n\n" +
        "-- ------------------------------\n-- TABLE DATA: scratch\n-- ------------------------------\n\n" +
        "INSERT [ { id: scratch:`émile`, name: 'Émile Zola', note: '日本語' } ];\n\n"

private const val DUPLICATES =
    """[{"details":{"details":{"id":"company:acme"},"kind":"Record"},"kind":"AlreadyExists",""" +
        """"result":"Database record `company:acme` already exists","status":"ERR","time":"49.667µs","type":null},""" +
        """{"details":{"details":{"id":"person:ada"},"kind":"Record"},"kind":"AlreadyExists",""" +
        """"result":"Database record `person:ada` already exists","status":"ERR","time":"21.291µs","type":null}]"""

private const val OPTION_IMPORT_RULE =
    "Invalid statement: Import requires `OPTION IMPORT;` as the first statement. This disables events, " +
        "live queries, field processing, and result output for optimal import performance. To execute queries " +
        "with full side effects, use the /sql endpoint instead."

private const val MISSING_OPTION_IMPORT =
    """{"code":400,"details":"Request problems detected","description":"There is a problem with your request. """ +
        """Refer to the documentation for further information.","information":"$OPTION_IMPORT_RULE"}"""

private const val FORBIDDEN =
    """{"code":403,"details":"Forbidden","description":"Not allowed to do this.",""" +
        """"information":"IAM error: Not enough permissions to perform this action"}"""

private fun MockRequestHandleScope.json(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData =
    respond(
        content,
        status,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

private fun MockRequestHandleScope.rpcToken(): HttpResponseData = json("""{"id":"1","result":"jwt.token"}""")

private fun MockRequestHandleScope.rpcNull(): HttpResponseData = json("""{"id":"1","result":null}""")

private fun client(
    url: String = "http://localhost:8000",
    autoAuthenticate: Boolean = false,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Surreal =
    Surreal(
        Surreal.Config(
            url = url,
            autoConnect = false,
            autoAuthenticate = autoAuthenticate,
            credentialProvider = if (autoAuthenticate) ({ Credentials.RootUser("root", "root") }) else null,
            httpClientFactory = { _ -> HttpClient(MockEngine(handler)) },
        ),
    )

private suspend fun Surreal.signedInSession(): Session {
    val db = session()
    db.signin(Credentials.RootUser("root", "root"))
    db.use(Namespace("main"), Database("main"))
    return db
}

private fun HttpRequestData.bodyText(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

private fun HttpRequestData.isRpc(): Boolean = url.encodedPath.endsWith("/rpc")

class ExportImportTest :
    ShouldSpec(
        {
            context("Session.exportSurql over HTTP") {
                for (base in listOf("http://localhost:8000", "http://localhost:8000/", "http://localhost:8000/rpc")) {
                    should("send GET /export beside the rpc endpoint for $base with the session as headers") {
                        runTest {
                            val requests = mutableListOf<HttpRequestData>()
                            val db =
                                client(base) { request ->
                                    requests += request
                                    when {
                                        request.isRpc() && request.bodyText().contains("\"signin\"") -> rpcToken()
                                        request.isRpc() -> rpcNull()
                                        else -> respond(EXPORT_TEXT, HttpStatusCode.OK)
                                    }
                                }.signedInSession()

                            db.exportSurql() shouldBe EXPORT_TEXT

                            val export = requests.last()
                            export.method shouldBe HttpMethod.Get
                            export.url.toString() shouldBe "http://localhost:8000/export"
                            export.headers[HttpHeaders.Authorization] shouldBe "Bearer jwt.token"
                            export.headers["Surreal-NS"] shouldBe "main"
                            export.headers["Surreal-DB"] shouldBe "main"
                        }
                    }
                }

                should("answer an empty string for an empty body, which is what an absent database exports") {
                    runTest {
                        val db = client { respond("", HttpStatusCode.OK) }.session()

                        db.exportSurql() shouldBe ""
                    }
                }

                should("throw SurrealAuthenticationException on the bare-text 401, so a provider gets its retry") {
                    runTest {
                        val db = client { respond("InvalidToken", HttpStatusCode.Unauthorized) }.session()

                        val failure = shouldThrow<SurrealAuthenticationException> { db.exportSurql() }

                        failure.code shouldBe 401
                        failure.message shouldBe "InvalidToken"
                        failure.isInvalidAuth shouldBe true
                        failure.data.shouldBeNull()
                    }
                }

                should("throw SurrealAuthenticationException on the JSON 403 with the server's sentence") {
                    runTest {
                        val db = client { json(FORBIDDEN, HttpStatusCode.Forbidden) }.session()

                        val failure = shouldThrow<SurrealAuthenticationException> { db.exportSurql() }

                        failure.code shouldBe 403
                        failure.message shouldBe "IAM error: Not enough permissions to perform this action"
                        failure.data
                            ?.jsonObject
                            ?.get("code")
                            ?.jsonPrimitive
                            ?.content shouldBe "403"
                    }
                }

                should("sign in through the credential provider after a 403 and succeed on the retry") {
                    runTest {
                        var exports = 0
                        val db =
                            client(autoAuthenticate = true) { request ->
                                when {
                                    request.isRpc() -> rpcToken()
                                    exports++ == 0 -> json(FORBIDDEN, HttpStatusCode.Forbidden)
                                    else -> respond(EXPORT_TEXT, HttpStatusCode.OK)
                                }
                            }.session()

                        db.exportSurql() shouldBe EXPORT_TEXT

                        exports shouldBe 2
                        db.accessToken() shouldBe "jwt.token"
                    }
                }

                should("throw SurrealTransportException naming the status on a 404 with no body") {
                    runTest {
                        val db = client { respond("", HttpStatusCode.NotFound) }.session()

                        val failure = shouldThrow<SurrealTransportException> { db.exportSurql() }

                        failure.message shouldContain "HTTP 404"
                    }
                }
            }

            context("Session.importSurql over HTTP") {
                should("POST the text to /import with Accept application/json and the session as headers") {
                    runTest {
                        val text = "OPTION IMPORT;\nCREATE scratch:`émile` SET name = 'Émile';\n"
                        val requests = mutableListOf<HttpRequestData>()
                        val db =
                            client { request ->
                                requests += request
                                when {
                                    request.isRpc() && request.bodyText().contains("\"signin\"") -> rpcToken()
                                    request.isRpc() -> rpcNull()
                                    else -> json("[]")
                                }
                            }.signedInSession()

                        db.importSurql(text)

                        val import = requests.last()
                        import.method shouldBe HttpMethod.Post
                        import.url.toString() shouldBe "http://localhost:8000/import"
                        import.headers[HttpHeaders.Accept] shouldBe "application/json"
                        import.body.contentType.toString() shouldBe "text/plain"
                        import.headers[HttpHeaders.Authorization] shouldBe "Bearer jwt.token"
                        import.headers["Surreal-NS"] shouldBe "main"
                        import.headers["Surreal-DB"] shouldBe "main"
                        import.bodyText() shouldBe text
                    }
                }

                should("throw the first failed statement typed by its kind, with every failure in data") {
                    runTest {
                        val db = client { json(DUPLICATES) }.session()

                        val failure = shouldThrow<SurrealAlreadyExistsException> { db.importSurql("OPTION IMPORT;\n") }

                        failure.message shouldBe
                            "2 statements failed during the import; the first: Database record `company:acme` already exists"
                        failure.detail shouldBe ErrorKind.AlreadyExists.Detail.Record("company:acme")
                        (failure.data as JsonArray) shouldHaveSize 2
                    }
                }

                should("keep the server's message as is when one statement failed") {
                    runTest {
                        val thrown =
                            """[{"kind":"Thrown","result":"An error occurred: stop here","status":"ERR",""" +
                                """"time":"39.375µs","type":null}]"""
                        val db = client { json(thrown) }.session()

                        val failure = shouldThrow<SurrealRpcException> { db.importSurql("OPTION IMPORT;\nTHROW 'x';") }

                        failure.message shouldBe "An error occurred: stop here"
                        failure.kind shouldBe ErrorKind.Thrown
                    }
                }

                should("surface the OPTION IMPORT rule as a SurrealRpcException with the 400 and the sentence") {
                    runTest {
                        val db = client { json(MISSING_OPTION_IMPORT, HttpStatusCode.BadRequest) }.session()

                        val failure = shouldThrow<SurrealRpcException> { db.importSurql("DEFINE TABLE person;") }

                        failure.code shouldBe 400
                        failure.message shouldBe OPTION_IMPORT_RULE
                        failure.kind shouldBe ErrorKind.Validation(ErrorKind.Validation.Detail.InvalidRequest)
                    }
                }
            }

            context("over WebSocket") {
                should("throw SurrealFeatureNotSupportedException from both members before any request") {
                    runTest {
                        val db = client("ws://localhost:8000") { error("no request expected over WebSocket") }.session()

                        shouldThrow<SurrealFeatureNotSupportedException> { db.exportSurql() }
                            .message shouldContain "ws://localhost:8000"
                        shouldThrow<SurrealFeatureNotSupportedException> { db.importSurql("OPTION IMPORT;") }
                            .message shouldContain "http:// or https://"
                    }
                }
            }
        },
    )
