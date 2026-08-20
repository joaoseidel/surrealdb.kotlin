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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
                    val rows = db.select(Lines).await().jsonArray

                    rows.map { it.jsonObject["title"]!!.jsonPrimitive.content } shouldBe listOf("One", "Two")
                }
            }

            should("return both when a where clause matches both") {
                onServer { db ->
                    val rows =
                        db
                            .select(Lines)
                            .where { n greaterEq 1 }
                            .await()
                            .jsonArray

                    rows.size shouldBe 2
                }
            }
        }

        context("an update over a table holding two records") {
            should("write to both and answer with a list, whatever the match count") {
                onServer { db ->
                    val updated =
                        db
                            .update(Lines)
                            .set { it[n] = 9 }
                            .await()
                            .jsonArray

                    updated.map { it.jsonObject["n"]!!.jsonPrimitive.int } shouldBe listOf(9, 9)
                }
            }
        }

        context("a record target") {
            should("answer with the record itself rather than a list of one") {
                onServer { db ->
                    val record = db.select(Lines["a"]).await()

                    record.jsonObject["title"]!!.jsonPrimitive.content shouldBe "One"
                }
            }

            should("answer with null for a record that does not exist, rather than failing") {
                onServer { db ->
                    db.select(Lines["nobody"]).await().toString() shouldBe "null"
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
                    val record =
                        db
                            .select(Lines)
                            .limit(1)
                            .only()
                            .await()

                    record.jsonObject["title"]!!.jsonPrimitive.content shouldBe "One"
                }
            }
        }

        context("a create over a table") {
            should("answer with the record it wrote rather than a list of one") {
                onServer { db ->
                    val created = db.create(Lines).set { it[title] = "Three" }.await()

                    created.jsonObject["title"]!!.jsonPrimitive.content shouldBe "Three"
                }
            }
        }
    })
