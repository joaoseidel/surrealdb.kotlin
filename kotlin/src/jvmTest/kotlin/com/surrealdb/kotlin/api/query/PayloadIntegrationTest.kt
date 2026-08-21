package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.Credentials
import com.surrealdb.kotlin.api.Database
import com.surrealdb.kotlin.api.Namespace
import com.surrealdb.kotlin.api.Session
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.Nested
import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.get
import com.surrealdb.kotlin.api.error.SurrealException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private object Cards : Table("pl_card") {
    val id = recordId()
    val name by field<String>()
    val note by field<String>()
    val tags by field<List<String>>()
    val firstTag = field<String>("tags[0]")
    val author = field<RecordId>("author")

    object Address : Nested("address") {
        val city by field<String>()
        val zip = field<String>("postal_code")

        object Geo : Nested("address.geo") {
            val lat by field<Double>()
            val lng by field<Double>()
        }

        val geo = nested(Geo)
    }

    val address = nested(Address)
}

private object Writers : Table("pl_writer") {
    val name by field<String>()
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
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            statementResults(
                db.query(
                    surql("REMOVE TABLE IF EXISTS ${Cards.tableName}; REMOVE TABLE IF EXISTS ${Writers.tableName}"),
                ),
            )

            db.create(Writers["ada"]).set { it[name] = "Ada" }.await()
            db
                .create(Cards["a"])
                .set {
                    it[name] = "Ada"
                    it[tags] = listOf("cs", "lisp")
                    it[address.city] = "Boston"
                    it[address.zip] = "02110"
                    it[address.geo.lat] = 42.36
                    it[address.geo.lng] = -71.06
                }.await()

            block(db)
        } finally {
            client.close()
        }
    }
}

class PayloadIntegrationTest :
    ShouldSpec({
        context("a merge over a nested field") {
            should("reach the field the path names, where the path sent as a key would not") {
                onServer { db ->
                    db.merge(Cards["a"]) { it[address.zip] = "99999" }.await()

                    val card = db.select(Cards["a"]).theRecord()
                    card[Cards.address.zip] shouldBe "99999"
                    card[Cards.address.city] shouldBe "Boston"
                    card.content.containsKey("address.postal_code") shouldBe false
                }
            }

            should("leave every field it did not name alone, at every level") {
                onServer { db ->
                    db.merge(Cards["a"]) { it[address.geo.lat] = 1.0 }.await()

                    val card = db.select(Cards["a"]).theRecord()
                    card[Cards.address.geo.lat] shouldBe 1.0
                    card[Cards.address.geo.lng] shouldBe -71.06
                    card[Cards.address.city] shouldBe "Boston"
                    card[Cards.name] shouldBe "Ada"
                }
            }

            should("write both fields of one object, rather than the last one written winning") {
                onServer { db ->
                    db
                        .merge(Cards["a"]) {
                            it[address.city] = "Cambridge"
                            it[address.zip] = "02139"
                        }.await()

                    val card = db.select(Cards["a"]).theRecord()
                    card[Cards.address.city] shouldBe "Cambridge"
                    card[Cards.address.zip] shouldBe "02139"
                }
            }
        }

        context("a merge over an array field") {
            should("replace the array whole, because MERGE does not append to one") {
                onServer { db ->
                    db.merge(Cards["a"]) { it[tags] = listOf("forth") }.await()

                    db.select(Cards["a"]).theRecord()[Cards.tags] shouldBe listOf("forth")
                }
            }
        }

        context("a merge over a link field") {
            should("read back as a record id, because the server parses the string form as one") {
                onServer { db ->
                    db.merge(Cards["a"]) { it[author] = RecordId(Writers.tableName, "ada") }.await()

                    db.select(Cards["a"]).theRecord()[Cards.author] shouldBe RecordId(Writers.tableName, "ada")
                }
            }
        }

        context("a payload whose keys are paths, sent through the JsonElement escape") {
            should("write a top-level key of that name and leave the field alone, with status OK") {
                onServer { db ->
                    db
                        .merge(
                            Cards["a"],
                            buildJsonObject { put("address.postal_code", JsonPrimitive("00000")) },
                        ).await()

                    val card = db.select(Cards["a"]).theRecord()
                    card.content.containsKey("address.postal_code") shouldBe true
                    card.content["address"]!!.toString() shouldContain "\"postal_code\":\"02110\""
                }
            }
        }

        context("a patch at an array index") {
            should("replace the element, which the server's own replace operation does not") {
                onServer { db ->
                    db.patch(Cards["a"]) { it.replace(firstTag, "forth") }.await()

                    db.select(Cards["a"]).theRecord()[Cards.tags] shouldBe listOf("forth", "lisp")
                }
            }

            should("insert and shift on an add, which is what RFC 6902 means by one") {
                onServer { db ->
                    db.patch(Cards["a"]) { it.add(firstTag, "forth") }.await()

                    db.select(Cards["a"]).theRecord()[Cards.tags] shouldBe listOf("forth", "cs", "lisp")
                }
            }

            should("drop the element on a remove") {
                onServer { db ->
                    db.patch(Cards["a"]) { it.remove(firstTag) }.await()

                    db.select(Cards["a"]).theRecord()[Cards.tags] shouldBe listOf("lisp")
                }
            }

            should("append at the end of the array") {
                onServer { db ->
                    db.patch(Cards["a"]) { it.append(tags, "forth") }.await()

                    db.select(Cards["a"]).theRecord()[Cards.tags] shouldBe listOf("cs", "lisp", "forth")
                }
            }
        }

        context("a patch over a nested field") {
            should("resolve into the object rather than adding a key named for the path") {
                onServer { db ->
                    db.patch(Cards["a"]) { it.replace(address.city, "Cambridge") }.await()

                    val card = db.select(Cards["a"]).theRecord()
                    card[Cards.address.city] shouldBe "Cambridge"
                    card.content.containsKey("address.city") shouldBe false
                }
            }

            should("move a value between fields, leaving nothing behind at the source") {
                onServer { db ->
                    db.patch(Cards["a"]) { it.move(from = name, to = note) }.await()

                    val card = db.select(Cards["a"]).theRecord()
                    card[Cards.note] shouldBe "Ada"
                    card.content.containsKey("name") shouldBe false
                }
            }
        }

        context("a patch whose test does not hold") {
            should("fail the statement and roll back the operations before it, because a patch is atomic") {
                onServer { db ->
                    val failure =
                        shouldThrow<SurrealException> {
                            db
                                .patch(Cards["a"]) {
                                    it.replace(name, "Grace")
                                    it.test(name, "Ada")
                                }.await()
                        }

                    failure.message.toString() shouldContain "test operation failed"
                    db.select(Cards["a"]).theRecord()[Cards.name] shouldBe "Ada"
                }
            }
        }

        context("a string a SurrealQL parser reads as a record id") {
            should("arrive truncated and as a link, because a bound string is parsed as a value first") {
                onServer { db ->
                    db.merge(Cards["a"]) { it[note] = "note: remember the milk" }.await()

                    db
                        .select(Cards["a"])
                        .theRecord()
                        .content["note"]!!
                        .toString() shouldBe "\"note:remember\""
                }
            }
        }
    })
