package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.data.Row
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.get
import com.surrealdb.kotlin.query.api.integrationEndpoint
import com.surrealdb.kotlin.query.api.integrationTestConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

/**
 * Runs each statement this phase can emit against a real server, because a
 * rendered string being the one we intended says nothing about SurrealDB
 * accepting it.
 *
 * Opt-in through `SURREAL_RUN_INTEGRATION=true`, so an ordinary `jvmTest` needs
 * no server.
 */
private fun onServer(block: suspend (Session) -> Unit) {
    runBlocking {
        val client = Surreal(Surreal.Config(url = integrationEndpoint()))
        val db = client.session()
        try {
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            db.query(surql("DEFINE TABLE OVERWRITE ${Books.tableName} SCHEMALESS"))
            db.query(surql("DELETE ${Books.tableName}"))

            block(db)
        } finally {
            client.close()
        }
    }
}

class WriteBuilderIntegrationTest :
    ShouldSpec(
        {
            defaultTestConfig = integrationTestConfig

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

            context("create with CONTENT") {
                should("write assigned fields via content block") {
                    onServer { db ->
                        val created =
                            db
                                .create(Books["content_sicp"])
                                .content {
                                    it[title] = "SICP Content"
                                    it[pages] = 657
                                }.theRecord()

                        created[Books.title] shouldBe "SICP Content"
                        created[Books.pages] shouldBe 657
                    }
                }
            }

            context("insert with CONTENT") {
                should("insert a record via insert content block") {
                    onServer { db ->
                        val inserted =
                            db
                                .insert(Books)
                                .content {
                                    it[title] = "Inserted Book"
                                    it[pages] = 200
                                }.theRecord()

                        inserted[Books.title] shouldBe "Inserted Book"
                        inserted[Books.pages] shouldBe 200
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

                        db
                            .update(Books["array"])
                            .set { it[tags] -= "cs" }
                            .theRecord()[Books.tags] shouldBe listOf("lisp")
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
        },
    )
