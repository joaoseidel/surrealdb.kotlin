package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Table
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class Person(
    val name: String,
)

/**
 * A context that records what it was asked to send and answers with the
 * `[{status, result}]` envelope a real `query` RPC returns.
 */
private class RecordingContext(
    override val json: Json = Json,
    private val result: JsonElement = JsonPrimitive("ok"),
) : QueryContext {
    val sent: MutableList<BoundQuery> = mutableListOf()

    override suspend fun query(bound: BoundQuery): JsonElement {
        sent += bound
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("status", "OK")
                    put("result", result)
                },
            )
        }
    }
}

/**
 * The execution contract behind the whole CRUD surface. A session and a
 * transaction differ only in how they answer [QueryContext.query]; everything
 * else is written once, so these cases hold for both.
 */
class QueryContextTest :
    ShouldSpec({
        context("QueryContext.query") {
            context("the raw string form") {
                should("carry the caller's bindings onto the bound form it derives") {
                    runTest {
                        val context = RecordingContext()

                        context.query(
                            "SELECT * FROM type::table(\$tb)",
                            buildJsonObject { put("tb", JsonPrimitive("person")) },
                        )

                        context.sent.single().surql shouldBe "SELECT * FROM type::table(\$tb)"
                        context.sent
                            .single()
                            .bindings["tb"] shouldBe JsonPrimitive("person")
                    }
                }

                should("attach no bindings when the caller passed none") {
                    runTest {
                        val context = RecordingContext()

                        context.query("SELECT 1")

                        context.sent.single().bindings shouldBe emptyMap()
                    }
                }
            }
        }

        context("the CRUD surface") {
            should("dispatch through the context that built the query, so a builder cannot reach a different one") {
                runTest {
                    val session = RecordingContext()
                    val transaction = RecordingContext()

                    transaction.select(Table("person")).await()

                    session.sent.shouldBeEmpty()
                    transaction.sent.single().surql shouldStartWith "SELECT * FROM type::table("
                }
            }

            should("decode with the context's own serializer, so a session's configuration reaches every builder") {
                runTest {
                    val context =
                        RecordingContext(
                            json = Json { ignoreUnknownKeys = true },
                            result =
                                buildJsonObject {
                                    put("name", JsonPrimitive("Ada"))
                                    put("unmodelled", JsonPrimitive(1))
                                },
                        )

                    val person = context.select(Table("person")).awaitAs<Person>()

                    person shouldBe Person("Ada")
                }
            }
        }
    })
