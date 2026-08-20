package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.Session
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.get
import com.surrealdb.kotlin.api.error.SurrealRpcException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private object Lines : Table("om_line") {
    val title by field<String>()
    val n by field<Int>()
}

private fun integrationEnabled() = System.getenv("SURREAL_RUN_INTEGRATION") == "true"

private fun endpoint() = System.getenv("SURREAL_JVM_ENDPOINT") ?: "http://127.0.0.1:8000"

private fun onServer(block: suspend (Session) -> Unit) {
    if (!integrationEnabled()) return

    runBlocking {
        val client = Surreal(Surreal.Config(url = endpoint()))
        val db = client.session()
        try {
            db.signin(
                buildJsonObject {
                    put("user", JsonPrimitive("root"))
                    put("pass", JsonPrimitive("root"))
                },
            )
            db.use("main", "main")
            db.query("DEFINE TABLE ${Lines.tableName} SCHEMALESS")
            db.query("DELETE ${Lines.tableName}")
            db
                .create(Lines["a"])
                .set {
                    it[title] = "One"
                    it[n] = 1
                }.await()
            db
                .create(Lines["b"])
                .set {
                    it[title] = "Two"
                    it[n] = 2
                }.await()

            block(db)
        } finally {
            client.close()
        }
    }
}

class OnlyModifierIntegrationTest :
    ShouldSpec({
        context("a select over a table holding two records") {
            should("return both of them") {
                onServer { db ->
                    val rows = db.select(Lines).await()

                    rows.map { it[Lines.title] } shouldBe listOf("One", "Two")
                }
            }

            should("return both when a where clause matches both") {
                onServer { db ->
                    val rows =
                        db
                            .select(Lines)
                            .where { n greaterEq 1 }
                            .await()

                    rows.size shouldBe 2
                }
            }
        }

        context("an update over a table holding two records") {
            should("write to both and answer with every record it touched, whatever the match count") {
                onServer { db ->
                    val updated =
                        db
                            .update(Lines)
                            .set { it[n] = 9 }
                            .await()

                    updated.map { it[Lines.n] } shouldBe listOf(9, 9)
                }
            }
        }

        context("a record target") {
            should("answer with the record itself rather than a list of one, which is what ONLY buys") {
                onServer { db ->
                    val statement = db.select(Lines["a"])

                    firstQueryResult(db.query(statement.compile())).shouldBeInstanceOf<JsonObject>()
                    statement.awaitSingleOrNull()?.get(Lines.title) shouldBe "One"
                }
            }

            should("answer with null for a record that does not exist, rather than failing") {
                onServer { db ->
                    val statement = db.select(Lines["nobody"])

                    firstQueryResult(db.query(statement.compile())) shouldBe JsonNull
                    statement.awaitSingleOrNull() shouldBe null
                    statement.await() shouldBe emptyList()
                }
            }
        }

        context("only() on a table target") {
            should("fail on the second matching record, because that is what ONLY means") {
                onServer { db ->
                    val failure = shouldThrow<SurrealRpcException> { db.select(Lines).only().await() }

                    failure.message.shouldContain("ONLY")
                }
            }

            should("answer with the record itself once a limit narrows it to one") {
                onServer { db ->
                    val statement =
                        db
                            .select(Lines)
                            .limit(1)
                            .only()

                    firstQueryResult(db.query(statement.compile())).shouldBeInstanceOf<JsonObject>()
                    statement.awaitSingleOrNull()?.get(Lines.title) shouldBe "One"
                }
            }
        }

        context("a table target without only()") {
            should("answer with a list, which is the shape the terminals read every result through") {
                onServer { db ->
                    firstQueryResult(db.query(db.select(Lines).compile())).shouldBeInstanceOf<JsonArray>()
                }
            }
        }

        context("a create over a table") {
            should("answer with the record it wrote rather than a list of one") {
                onServer { db ->
                    val created = db.create(Lines).set { it[title] = "Three" }.awaitSingleOrNull()

                    created?.get(Lines.title) shouldBe "Three"
                }
            }
        }
    })
