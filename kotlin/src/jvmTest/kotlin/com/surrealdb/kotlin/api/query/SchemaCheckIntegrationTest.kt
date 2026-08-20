package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.Session
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.Table
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Everything here is defined on the server, except `id`, which never is. */
private object Readers : Table("sc_reader") {
    val id by field<String>()
    val name by field<String>()
    val age by field<Int>()
    val firstTag = field<String>("tags[0]")
}

/** The declaration the server has never heard of, which is the case that matters. */
private object Stale : Table("sc_reader") {
    val name by field<String>()
    val pagse by field<Int>()
}

private object Loose : Table("sc_loose") {
    val name by field<String>()
}

private object Absent : Table("sc_absent") {
    val name by field<String>()
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
            statementResults(
                db.query(
                    """
                    REMOVE TABLE IF EXISTS ${Readers.tableName};
                    REMOVE TABLE IF EXISTS ${Loose.tableName};
                    REMOVE TABLE IF EXISTS ${Absent.tableName};
                    DEFINE TABLE ${Readers.tableName} SCHEMAFULL;
                    DEFINE FIELD name ON ${Readers.tableName} TYPE string;
                    DEFINE FIELD age ON ${Readers.tableName} TYPE int;
                    DEFINE FIELD tags ON ${Readers.tableName} TYPE array<string>;
                    DEFINE TABLE ${Loose.tableName} SCHEMALESS;
                    """.trimIndent(),
                ),
            )

            block(db)
        } finally {
            client.close()
        }
    }
}

class SchemaCheckIntegrationTest :
    ShouldSpec({
        context("checkSchema on a SCHEMAFULL table") {
            should("report nothing when the server defines every declared field") {
                onServer { db ->
                    db.checkSchema(Readers) shouldBe emptyList()
                }
            }

            should("name the field the server does not have, and list the ones it does") {
                onServer { db ->
                    val drift = db.checkSchema(Stale)

                    drift shouldHaveSize 1
                    drift.single() shouldContain "${Stale.tableName}.pagse is not defined on the server"
                    drift.single() shouldContain "age"
                    drift.single() shouldContain "name"
                }
            }
        }

        context("checkSchema on a table the server cannot describe") {
            should("say a SCHEMALESS table has no field list, rather than passing quietly") {
                onServer { db ->
                    db.checkSchema(Loose).single() shouldBe
                        "${Loose.tableName} is not SCHEMAFULL, so the server does not know which fields it has."
                }
            }

            should("say an undefined table cannot be checked, rather than passing quietly") {
                onServer { db ->
                    db.checkSchema(Absent).single() shouldBe
                        "${Absent.tableName} is not defined on the server, so its fields cannot be checked."
                }
            }
        }

        context("checkSchema over several tables") {
            should("answer for each of them in one round trip") {
                onServer { db ->
                    db.checkSchema(Readers, Stale, Loose) shouldHaveSize 2
                }
            }
        }
    })
