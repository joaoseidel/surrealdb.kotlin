package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.Session
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.get
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class Shelf(
    val city: String,
    @SerialName("postal_code") val zip: String,
)

@Serializable
private data class Book(
    val title: String,
    val pages: Int,
    val tags: List<String>,
    val shelf: Shelf,
)

private object Books : Table<Book>("wb_book", Book.serializer()) {
    val title by field<String>()
    val pages by field<Int>()
    val tags by field<List<String>>()
    val shelf by nested<Shelf>()
    val shelfZip = field<String>("shelf.postal_code")
}

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
            db.query("DEFINE TABLE ${Books.tableName} SCHEMALESS")
            db.query("DELETE ${Books.tableName}")

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
                            }.await()

                    created.jsonObject["title"]!!.jsonPrimitive.content shouldBe "SICP"
                    created.jsonObject["pages"]!!.jsonPrimitive.int shouldBe 657
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
                                it[shelf[Shelf::city]] = "Boston"
                                it[shelfZip] = "02110"
                            }.await()

                    val shelf = updated.jsonObject["shelf"]!!.jsonObject
                    shelf["city"]!!.jsonPrimitive.content shouldBe "Boston"
                    shelf["postal_code"]!!.jsonPrimitive.content shouldBe "02110"
                }
            }
        }

        context("the array operators") {
            should("create the array on first append, then append and remove") {
                onServer { db ->
                    db.create(Books["array"]).set { it[title] = "Arrays" }.await()

                    val appended = db.update(Books["array"]).set { it[tags] += "cs" }.await()
                    appended.jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("cs")

                    val more = db.update(Books["array"]).set { it[tags] += "lisp" }.await()
                    more.jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                        listOf("cs", "lisp")

                    val removed = db.update(Books["array"]).set { it[tags] -= "cs" }.await()
                    removed.jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("lisp")
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
                            .await()

                    replaced.jsonObject["shelf"]!!.jsonObject shouldBe
                        buildJsonObject {
                            put("city", JsonPrimitive("Cambridge"))
                            put("postal_code", JsonPrimitive("02139"))
                        }
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

                    val unchanged = db.update(Books["empty"]).set { }.await()

                    unchanged.jsonObject["title"]!!.jsonPrimitive.content shouldBe "Untouched"
                    unchanged.jsonObject["pages"]!!.jsonPrimitive.int shouldBe 100
                }
            }

            should("create nothing when the record does not exist") {
                onServer { db ->
                    db.update(Books["absent"]).set { }.await() shouldBe JsonNull

                    db.select(Books["absent"]).await() shouldBe JsonNull
                }
            }
        }
    })
