package com.surrealdb.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assumptions.assumeTrue

class SurrealJvmIntegrationTest {
    @Test
    fun `runs full rpc and live integration flow`() = runBlocking {
        assumeTrue(System.getenv("SURREAL_RUN_INTEGRATION") == "true")
        val endpoint = System.getenv("SURREAL_JVM_ENDPOINT") ?: "http://127.0.0.1:8000"
        val runLive = System.getenv("SURREAL_RUN_LIVE_INTEGRATION") == "true"

        val client = SurrealClient(SurrealClientConfig(httpEndpoint = endpoint))
        try {
            client.signin(
                buildJsonObject {
                    put("user", JsonPrimitive("root"))
                    put("pass", JsonPrimitive("root"))
                },
            )
            client.use("main", "main")
            client.ping()
            client.version()
            client.info()
            client.query("DEFINE TABLE person SCHEMALESS")
            client.query("DEFINE TABLE likes SCHEMALESS")

            val created = client.create(
                thing = "person:chiru",
                data = buildJsonObject {
                    put("name", JsonPrimitive("Chiru"))
                    put("age", JsonPrimitive(30))
                },
            )
            assertTrue(created.jsonArray.isNotEmpty())

            client.insert(
                thing = "person",
                data = buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive("person:ada"))
                            put("name", JsonPrimitive("Ada"))
                        },
                    )
                },
            )

            client.update(
                thing = "person:chiru",
                data = buildJsonObject {
                    put("name", JsonPrimitive("Chiru B"))
                },
            )

            client.merge(
                thing = "person:chiru",
                data = buildJsonObject {
                    put("active", JsonPrimitive(true))
                },
            )

            client.patch(
                thing = "person:chiru",
                patches = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("op", JsonPrimitive("replace"))
                            put("path", JsonPrimitive("/name"))
                            put("value", JsonPrimitive("Chiru C"))
                        }
                    ),
                ),
            )

            client.relate(
                inRecord = "person:chiru",
                relation = "likes",
                outRecord = "person:ada",
                data = buildJsonObject {
                    put("strength", JsonPrimitive("high"))
                },
            )

            client.`let`("tb", JsonPrimitive("person"))
            val queryResult = client.query("SELECT * FROM type::table(\$tb)")
            assertTrue(queryResult.jsonArray.isNotEmpty())
            client.unset("tb")

            if (runLive) {
                val subscription = client.live("LIVE SELECT * FROM person")
                client.create(
                    thing = "person:live",
                    data = buildJsonObject {
                        put("name", JsonPrimitive("Live"))
                    },
                )

                val event = withTimeout(10_000) {
                    subscription.events.first()
                }
                assertEquals("CREATE", event.action)

                client.kill(subscription.id)
                subscription.cancel()
                client.delete("person:live")
            }

            client.invalidate()
        } finally {
            client.close()
        }
    }
}
