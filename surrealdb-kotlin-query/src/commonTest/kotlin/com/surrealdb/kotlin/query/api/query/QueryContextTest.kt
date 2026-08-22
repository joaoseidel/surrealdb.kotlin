package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Table
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The execution contract behind the full CRUD API. A session and a
 * transaction differ only in how they answer [QueryContext.query]; everything
 * else is written once, so these cases hold for both.
 */
class QueryContextTest :
    ShouldSpec({
        context("QueryContext.query") {
            context("a SurrealQL template") {
                should("carry the caller's bindings into the request") {
                    runTest {
                        val context = RecordingContext()

                        context.query(surqlTemplate { "SELECT * FROM type::table(${bind("person")})" })

                        context.sent.single().surql shouldBe "SELECT * FROM type::table(\$_0)"
                        context.sent
                            .single()
                            .bindings["_0"] shouldBe JsonPrimitive("person")
                    }
                }

                should("send no bindings when the template binds no values") {
                    runTest {
                        val context = RecordingContext()

                        context.query(surqlTemplate { "SELECT 1" })

                        context.sent.single().bindings shouldBe emptyMap()
                    }
                }
            }
        }

        context("the CRUD API") {
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
