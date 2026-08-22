package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.Credentials
import com.surrealdb.kotlin.api.Database
import com.surrealdb.kotlin.api.Namespace
import com.surrealdb.kotlin.api.Session
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.Nested
import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.get
import com.surrealdb.kotlin.api.error.SurrealProtocolException
import com.surrealdb.kotlin.api.integrationEndpoint
import com.surrealdb.kotlin.api.integrationTestConfig
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

    object Venue : Nested("venue") {
        val city by field<String>()
        val country by field<String>()
    }

    val venue = nested(Venue)
}

private suspend fun Query.theRecord(): Row = awaitSingleOrNull() ?: error("the statement answered with no record")

private fun onServer(block: suspend (Session) -> Unit) {
    runBlocking {
        val client = Surreal(Surreal.Config(url = integrationEndpoint()))
        val db = client.session()
        try {
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            statementResults(db.query(surql("REMOVE TABLE IF EXISTS ${Talks.tableName}")))

            db
                .create(Talks["ada"])
                .set {
                    it[title] = "Ada"
                    it[tags] = listOf("cs", "lisp")
                    it[venue.city] = "Boston"
                    it[venue.country] = "US"
                }.await()
            db.create(Talks["grace"]).set { it[title] = "Grace" }.await()

            block(db)
        } finally {
            client.close()
        }
    }
}

class ProjectionIntegrationTest :
    ShouldSpec({
        defaultTestConfig = integrationTestConfig

        context("a projection over a nested field") {
            should("read back through the field that named it, since the server rebuilds the object") {
                onServer { db ->
                    val talk = db.select(Talks["ada"]).fields(Talks.title, Talks.venue.city).theRecord()

                    talk[Talks.title] shouldBe "Ada"
                    talk[Talks.venue.city] shouldBe "Boston"
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

        context("a projection over a nested group") {
            should("answer with the whole object, read leaf by leaf") {
                onServer { db ->
                    val talk = db.select(Talks["ada"]).fields(Talks.venue).theRecord()

                    talk[Talks.venue.city] shouldBe "Boston"
                    talk[Talks.venue.country] shouldBe "US"
                }
            }
        }

        context("a VALUE projection") {
            should("read as the values themselves, in the order the statement answered") {
                onServer { db ->
                    val cities =
                        db
                            .select(Talks)
                            .value(Talks.venue.city)
                            .decodeAs<String?>()
                            .await()

                    cities shouldBe listOf("Boston", null)
                }
            }

            should("refuse await(), which reads records, and name the terminal that reads a value") {
                onServer { db ->
                    val failure =
                        shouldThrow<SurrealProtocolException> {
                            db.select(Talks["ada"]).value(Talks.venue.city).await()
                        }

                    failure.message shouldContain "decodeAs()"
                }
            }
        }
    })
