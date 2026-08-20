package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Table
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        }
    })
