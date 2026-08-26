package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.integrationEndpoint
import com.surrealdb.kotlin.query.api.integrationTestConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private object InSync : Table("sc_reader") {
    val id by field<String>()
    val name by field<String>()
    val age by field<Int>()
    val firstTag = field<String>("tags[0]")
}

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

private fun onServer(block: suspend (Session) -> Unit) {
    runBlocking {
        val client = Surreal(Surreal.Config(url = integrationEndpoint()))
        val db = client.session()
        try {
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            statementResults(
                db.query(
                    surql(
                        """
                        REMOVE TABLE IF EXISTS ${InSync.tableName};
                        REMOVE TABLE IF EXISTS ${Loose.tableName};
                        REMOVE TABLE IF EXISTS ${Absent.tableName};
                        DEFINE TABLE ${InSync.tableName} SCHEMAFULL;
                        DEFINE FIELD name ON ${InSync.tableName} TYPE string;
                        DEFINE FIELD age ON ${InSync.tableName} TYPE int;
                        DEFINE FIELD tags ON ${InSync.tableName} TYPE array<string>;
                        DEFINE TABLE ${Loose.tableName} SCHEMALESS;
                        """.trimIndent(),
                    ),
                ),
            )

            block(db)
        } finally {
            client.close()
        }
    }
}

class SchemaCheckIntegrationTest :
    ShouldSpec(
        {
            defaultTestConfig = integrationTestConfig

            context("checkSchema on a SCHEMAFULL table") {
                should("report nothing when the server defines every declared field") {
                    onServer { db ->
                        db.checkSchema(InSync) shouldBe emptyList()
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
                        db.checkSchema(InSync, Stale, Loose) shouldHaveSize 2
                    }
                }
            }
        },
    )
