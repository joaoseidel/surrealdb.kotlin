package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.Session
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.get
import com.surrealdb.kotlin.api.error.SurrealProtocolException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private object Talks : Table("pj_talk") {
    val title by field<String>()
    val nickname by field<String?>()
    val tags by field<List<String>>()
    val firstTag = field<String>("tags[0]")
    val secondTag = field<String>("tags[1]")
    val everyTag = field<List<String>>("tags[*]")
    val city = field<String>("venue.city")
}

private suspend fun Query.theRecord(): Row = awaitSingleOrNull() ?: error("the statement answered with no record")

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
            statementResults(db.query("REMOVE TABLE IF EXISTS ${Talks.tableName}"))

            db
                .create(Talks["ada"])
                .set {
                    it[title] = "Ada"
                    it[tags] = listOf("cs", "lisp")
                    it[city] = "Boston"
                }.await()
            db.create(Talks["grace"]).set { it[title] = "Grace" }.await()

            block(db)
        } finally {
            client.close()
        }
    }
}

/**
 * Every projection here is sent to a real SurrealDB, because the value's
 * arrival is the server's decision and not this library's.
 */
class ProjectionIntegrationTest :
    ShouldSpec({
        context("a projection over a nested field") {
            should("read back through the field that named it, since the server rebuilds the object") {
                onServer { db ->
                    val talk = db.select(Talks["ada"]).fields(Talks.title, Talks.city).theRecord()

                    talk[Talks.title] shouldBe "Ada"
                    talk[Talks.city] shouldBe "Boston"
                }
            }
        }

        context("a projection over an indexed path") {
            should("read back at the index it named, which an unaliased projection loses") {
                onServer { db ->
                    db.select(Talks["ada"]).fields(Talks.firstTag).theRecord()[Talks.firstTag] shouldBe "cs"
                }
            }

            should("keep two indexes into one array apart") {
                onServer { db ->
                    val talk = db.select(Talks["ada"]).fields(Talks.firstTag, Talks.secondTag).theRecord()

                    talk[Talks.firstTag] shouldBe "cs"
                    talk[Talks.secondTag] shouldBe "lisp"
                }
            }

            should("read every element back") {
                onServer { db ->
                    db.select(Talks["ada"]).fields(Talks.everyTag).theRecord()[Talks.everyTag] shouldBe
                        listOf("cs", "lisp")
                }
            }
        }

        context("a projected field the record does not carry") {
            should("arrive as an explicit null, where the whole record leaves it out") {
                onServer { db ->
                    val projected = db.select(Talks["grace"]).fields(Talks.nickname).theRecord()
                    val whole = db.select(Talks["grace"]).theRecord()

                    projected.content shouldBe buildJsonObject { put("nickname", JsonNull) }
                    projected[Talks.nickname] shouldBe null
                    whole.content.containsKey("nickname") shouldBe false
                }
            }
        }

        context("a VALUE projection") {
            should("read as the values themselves, in the order the statement answered") {
                onServer { db ->
                    val cities =
                        db
                            .select(Talks)
                            .value(Talks.city)
                            .decodeAs<String?>()
                            .await()

                    cities shouldBe listOf("Boston", null)
                }
            }

            should("refuse await(), which reads records, and name the terminal that reads a value") {
                onServer { db ->
                    val failure =
                        shouldThrow<SurrealProtocolException> {
                            db.select(Talks["ada"]).value(Talks.city).await()
                        }

                    failure.message shouldContain "decodeAs()"
                }
            }
        }
    })
