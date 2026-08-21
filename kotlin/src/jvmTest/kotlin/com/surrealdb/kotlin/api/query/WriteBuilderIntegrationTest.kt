package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.Session
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.get
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
private data class Shelf(
    val city: String,
    @SerialName("postal_code") val zip: String,
)

private object Books : Table("wb_book") {
    val title by field<String>()
    val pages by field<Int>()
    val tags by field<List<String>>()
    val shelf = field<Shelf>("shelf")
    val shelfCity = field<String>("shelf.city")
    val shelfZip = field<String>("shelf.postal_code")
}

private suspend fun Query.theRecord(): Row = awaitSingleOrNull() ?: error("the statement answered with no record")

private fun integrationEnabled() = System.getenv("SURREAL_RUN_INTEGRATION") == "true"

private fun endpoint() = System.getenv("SURREAL_JVM_ENDPOINT") ?: "http://127.0.0.1:8000"

/**
 * Runs each statement this phase can emit against a real server, because a
 * rendered string being the one we intended says nothing about SurrealDB
 * accepting it.
 *
 * Opt-in through `SURREAL_RUN_INTEGRATION=true`, so an ordinary `jvmTest` needs
 * no server.
 */
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
            db.query(surql("DEFINE TABLE ${Books.tableName} SCHEMALESS"))
            db.query(surql("DELETE ${Books.tableName}"))

            block(db)
        } finally {
            client.close()
        }
    }
}

class WriteBuilderIntegrationTest :
    ShouldSpec({
        context("create with SET") {
            should("write every assigned field") {
                onServer { db ->
                    val created =
                        db
                            .create(Books["sicp"])
                            .set {
                                it[title] = "SICP"
                                it[pages] = 657
                            }.theRecord()

                    created[Books.title] shouldBe "SICP"
                    created[Books.pages] shouldBe 657
                }
            }
        }

        context("a dotted path") {
            should("build the missing parent object rather than failing") {
                onServer { db ->
                    db.create(Books["htdp"]).set { it[title] = "HtDP" }.await()

                    val updated =
                        db
                            .update(Books["htdp"])
                            .set {
                                it[shelfCity] = "Boston"
                                it[shelfZip] = "02110"
                            }.theRecord()

                    updated[Books.shelfCity] shouldBe "Boston"
                    updated[Books.shelfZip] shouldBe "02110"
                }
            }
        }

        context("the array operators") {
            should("create the array on first append, then append and remove") {
                onServer { db ->
                    db.create(Books["array"]).set { it[title] = "Arrays" }.await()

                    db.update(Books["array"]).set { it[tags] += "cs" }.theRecord()[Books.tags] shouldBe listOf("cs")

                    db.update(Books["array"]).set { it[tags] += "lisp" }.theRecord()[Books.tags] shouldBe
                        listOf("cs", "lisp")

                    db.update(Books["array"]).set { it[tags] -= "cs" }.theRecord()[Books.tags] shouldBe listOf("lisp")
                }
            }
        }

        context("a composite assignment") {
            should("replace the whole object rather than merging into it") {
                onServer { db ->
                    db
                        .create(Books["composite"])
                        .set {
                            it[title] = "Composites"
                            it[shelf] = Shelf("Boston", "02110")
                        }.await()

                    val replaced =
                        db
                            .update(Books["composite"])
                            .set { it[shelf] = Shelf("Cambridge", "02139") }
                            .theRecord()

                    replaced[Books.shelf] shouldBe Shelf("Cambridge", "02139")
                }
            }
        }

        context("an empty set block") {
            should("leave the record untouched instead of failing to parse") {
                onServer { db ->
                    db
                        .create(Books["empty"])
                        .set {
                            it[title] = "Untouched"
                            it[pages] = 100
                        }.await()

                    val unchanged = db.update(Books["empty"]).set { }.theRecord()

                    unchanged[Books.title] shouldBe "Untouched"
                    unchanged[Books.pages] shouldBe 100
                }
            }

            should("create nothing when the record does not exist") {
                onServer { db ->
                    db
                        .update(Books["absent"])
                        .set { }
                        .await()
                        .shouldBeEmpty()

                    db.select(Books["absent"]).awaitSingleOrNull() shouldBe null
                }
            }
        }
    })
