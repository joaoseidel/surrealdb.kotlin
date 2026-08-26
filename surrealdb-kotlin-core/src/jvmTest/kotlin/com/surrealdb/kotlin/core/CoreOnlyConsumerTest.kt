package com.surrealdb.kotlin.core

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.beginTransaction
import com.surrealdb.kotlin.core.api.live.LiveMode
import com.surrealdb.kotlin.core.api.query.BoundQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CoreOnlyConsumerTest {
    @Test
    fun `sends a bound query with only core on the compile classpath`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"id":"1","result":[{"status":"OK","result":[{"value":"core"}]}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val client =
                Surreal(
                    Surreal.Config(
                        url = "http://localhost:8000",
                        autoConnect = false,
                        httpClientFactory = { HttpClient(engine) },
                    ),
                )

            val result =
                client
                    .session()
                    .query(BoundQuery().appendLiteral("SELECT * FROM person"))

            assertEquals(
                "core",
                result
                    .single()
                    .content["value"]
                    ?.jsonPrimitive
                    ?.content,
            )
            client.close()
        }

    @Test
    fun `core public workflow compiles without query builders`() {
        val workflow: suspend (Surreal, Session) -> Unit = { client, session ->
            client.connect()
            session.signin(Credentials.RootUser("root", "root"))
            session.use(Namespace("main"), Database("main"))

            val query =
                BoundQuery()
                    .appendLiteral("RETURN ")
                    .bind(JsonPrimitive("core"))
            session.query(query)

            val transaction = session.beginTransaction()
            transaction.query(BoundQuery().appendLiteral("RETURN true"))
            transaction.cancel()

            session.live("person", LiveMode.Records).cancel()
        }

        assertNotNull(workflow)
    }
}
