package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.query.create
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Common-tier tests for client-side transactions: verify the HTTP engine
 * rejects transactions with a clear feature-not-supported error. The
 * happy-path round-trip (begin → query-with-txn → commit/cancel) lives in
 * `jvmTest/SurrealJvmIntegrationTest.kt` because it requires a real
 * SurrealDB WebSocket.
 */
class TransactionTest {
    private suspend fun httpSession(): Session = httpClient().session()

    private fun httpClient(): Surreal {
        val engine =
            MockEngine { _ ->
                respond(
                    content = """{"id":"1","result":null}""",
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

    @Test
    fun `beginTransaction on http engine reports feature not supported`() =
        runTest {
            assertFailsWith<com.surrealdb.kotlin.api.error.SurrealFeatureNotSupportedException> {
                httpSession().beginTransaction()
            }
        }

    @Test
    fun `transaction block on http engine surfaces the feature error`() =
        runTest {
            val db = httpSession()
            assertFailsWith<com.surrealdb.kotlin.api.error.SurrealFeatureNotSupportedException> {
                db.transaction {
                    create(Table("person")).content(buildJsonObject {}).await()
                }
            }
        }
}
