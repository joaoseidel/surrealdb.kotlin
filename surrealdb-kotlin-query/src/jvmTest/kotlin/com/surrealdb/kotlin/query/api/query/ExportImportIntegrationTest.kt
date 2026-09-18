package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Feature
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.error.SurrealAlreadyExistsException
import com.surrealdb.kotlin.core.api.error.SurrealFeatureNotSupportedException
import com.surrealdb.kotlin.core.api.error.SurrealRpcException
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.integrationEndpoint
import com.surrealdb.kotlin.query.api.integrationTestConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray

private object Poets : Table("ei_poet") {
    val name by field<String>()
    val born by field<Int>()
}

private object Poems : Table("ei_poem") {
    val title by field<String>()
    val author by field<RecordId>()
}

private val source = Database("ei_source")
private val target = Database("ei_target")

private val seed =
    """
    OPTION IMPORT;
    DEFINE TABLE ${Poets.tableName} SCHEMAFULL;
    DEFINE FIELD name ON ${Poets.tableName} TYPE string;
    DEFINE FIELD born ON ${Poets.tableName} TYPE int;
    DEFINE TABLE ${Poems.tableName} SCHEMAFULL;
    DEFINE FIELD title ON ${Poems.tableName} TYPE string;
    DEFINE FIELD author ON ${Poems.tableName} TYPE record<${Poets.tableName}>;
    CREATE ${Poets.tableName}:lovelace SET name = 'Ada Lovelace', born = 1815;
    CREATE ${Poets.tableName}:hopper SET name = 'Grace Hopper', born = 1906;
    CREATE ${Poems.tableName}:notes SET title = 'Notes on the Analytical Engine', author = ${Poets.tableName}:lovelace;
    """.trimIndent()

private fun httpEndpoint(): String = integrationEndpoint().replace("ws://", "http://").replace("wss://", "https://")

private fun wsEndpoint(): String = integrationEndpoint().replace("http://", "ws://").replace("https://", "wss://")

private fun onServer(
    endpoint: String = httpEndpoint(),
    block: suspend (Surreal, Session) -> Unit,
) {
    runBlocking {
        val client = Surreal(Surreal.Config(url = endpoint))
        val db = client.session()
        try {
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            db.query(
                surql(
                    """
                    REMOVE DATABASE IF EXISTS ${source.value}; DEFINE DATABASE ${source.value};
                    REMOVE DATABASE IF EXISTS ${target.value}; DEFINE DATABASE ${target.value};
                    """.trimIndent(),
                ),
            )

            block(client, db)
        } finally {
            client.close()
        }
    }
}

private suspend fun Session.seeded(): Session {
    use(Namespace("main"), source)
    importSurql(seed)
    return this
}

class ExportImportIntegrationTest :
    ShouldSpec(
        {
            defaultTestConfig = integrationTestConfig

            context("Session.exportSurql and importSurql against SurrealDB") {
                should("export two tables and import them into an empty database, with checkSchema agreeing on both") {
                    onServer { _, db ->
                        db.seeded()
                        db.checkSchema(Poets, Poems).shouldBeEmpty()

                        val exported = db.exportSurql()
                        db.use(Namespace("main"), target)
                        db.importSurql(exported)

                        exported shouldStartWith "-- ------------------------------\n-- OPTION\n"
                        exported shouldContain "OPTION IMPORT;"
                        db.checkSchema(Poets, Poems).shouldBeEmpty()
                        db.select(Poets).await() shouldHaveSize 2
                        db.select(Poems).await() shouldHaveSize 1
                        db.exportSurql() shouldBe exported
                    }
                }

                should("report the statements the import rejected, typed, and keep the ones it applied") {
                    onServer { _, db ->
                        val exported = db.seeded().exportSurql()

                        val failure = shouldThrow<SurrealAlreadyExistsException> { db.importSurql(exported) }

                        (failure.data as JsonArray) shouldHaveSize 2
                        failure.message shouldContain "2 statements failed"
                        db.select(Poets).await() shouldHaveSize 2
                        db.select(Poems).await() shouldHaveSize 1
                    }
                }

                should("refuse text whose first statement is not OPTION IMPORT with the server's message") {
                    onServer { _, db ->
                        db.use(Namespace("main"), source)

                        val failure =
                            shouldThrow<SurrealRpcException> {
                                db.importSurql("DEFINE TABLE ${Poets.tableName} SCHEMALESS;")
                            }

                        failure.code shouldBe 400
                        failure.message shouldContain "OPTION IMPORT"
                    }
                }

                should("throw before any request over WebSocket") {
                    onServer(wsEndpoint()) { client, db ->
                        (Feature.ExportImport in client.features) shouldBe false
                        shouldThrow<SurrealFeatureNotSupportedException> { db.exportSurql() }
                        shouldThrow<SurrealFeatureNotSupportedException> { db.importSurql(seed) }
                    }
                }
            }
        },
    )
