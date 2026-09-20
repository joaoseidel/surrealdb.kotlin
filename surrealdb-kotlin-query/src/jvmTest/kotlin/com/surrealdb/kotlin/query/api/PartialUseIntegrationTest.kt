package com.surrealdb.kotlin.query.api

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.error.SurrealRpcException
import com.surrealdb.kotlin.query.api.query.surql
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val ACCESS = "pu_acct"
private const val EMAIL = "grace@example.com"
private const val RECORD_PASS = "pu_record_pass"

private val MAIN = Namespace("main")
private val MAIN_DB = Database("main")
private val OTHER_DB = Database("pu_other")

private val FIXTURES =
    """
    REMOVE DATABASE IF EXISTS ${OTHER_DB.value};
    DEFINE DATABASE ${OTHER_DB.value};
    REMOVE TABLE IF EXISTS pu_marker;
    REMOVE TABLE IF EXISTS pu_person;
    REMOVE ACCESS IF EXISTS $ACCESS ON DATABASE;
    CREATE pu_marker:one SET note = 'main';
    DEFINE TABLE pu_person SCHEMALESS PERMISSIONS FOR select WHERE id = ${'$'}auth;
    DEFINE ACCESS $ACCESS ON DATABASE TYPE RECORD
        SIGNUP ( CREATE pu_person SET email = ${'$'}email, pass = crypto::argon2::generate(${'$'}pass) )
        SIGNIN ( SELECT * FROM pu_person WHERE email = ${'$'}email AND crypto::argon2::compare(pass, ${'$'}pass) )
        DURATION FOR TOKEN 1h, FOR SESSION 2h;
    USE DB ${OTHER_DB.value};
    CREATE pu_marker:one SET note = 'other';
    """.trimIndent()

private val TEARDOWN =
    """
    REMOVE ACCESS IF EXISTS $ACCESS ON DATABASE;
    REMOVE TABLE IF EXISTS pu_person;
    REMOVE TABLE IF EXISTS pu_marker;
    REMOVE DATABASE IF EXISTS ${OTHER_DB.value};
    """.trimIndent()

private val RECORD_VARS: JsonObject =
    buildJsonObject {
        put("email", EMAIL)
        put("pass", RECORD_PASS)
    }

private fun onServer(
    config: Surreal.Config = Surreal.Config(url = integrationEndpoint()),
    block: suspend (Surreal, Session) -> Unit,
) {
    runBlocking {
        val client = Surreal(config)
        val root = client.session()
        try {
            root.signin(Credentials.RootUser("root", "root"))
            root.use(MAIN, MAIN_DB)
            root.query(surql(FIXTURES))

            block(client, root)
        } finally {
            runCatching { root.query(surql(TEARDOWN)) }
            client.close()
        }
    }
}

private suspend fun Session.recordToken(): String {
    val tokens = signup(Credentials.RecordUser(MAIN, MAIN_DB, ACCESS, RECORD_VARS))
    return tokens.shouldNotBeNull().accessToken
}

private suspend fun Session.marker(): String =
    query(surql("SELECT * FROM ONLY pu_marker:one"))
        .first()
        .content["note"]
        ?.jsonPrimitive
        ?.content
        .orEmpty()

class PartialUseIntegrationTest :
    ShouldSpec(
        {
            defaultTestConfig = integrationTestConfig

            context("Session.useDefaults") {
                should(
                    "let a record user authenticate, take the token's defaults and query without naming a namespace",
                ) {
                    onServer { client, root ->
                        val token = client.session().recordToken()
                        val db = client.session()
                        db.authenticate(token)

                        db.useDefaults()

                        db.namespace() shouldBe MAIN.value
                        db.database() shouldBe MAIN_DB.value
                        db
                            .query(surql("SELECT * FROM pu_person"))
                            .single()
                            .content["email"]
                            ?.jsonPrimitive
                            ?.content shouldBe EMAIL
                    }
                }

                should("select the server's defaults for a root token, which carries no namespace") {
                    onServer { client, root ->
                        val defaults =
                            root
                                .query(surql("INFO FOR ROOT"))
                                .first()
                                .content["defaults"] as? JsonObject
                        val expectedNamespace = defaults?.get("namespace")?.jsonPrimitive?.content
                        val db = client.session()
                        db.signin(Credentials.RootUser("root", "root"))

                        db.useDefaults()

                        db.namespace() shouldBe expectedNamespace
                        db.database() shouldBe defaults?.get("database")?.jsonPrimitive?.content
                    }
                }

                should("leave a pair chosen with use alone") {
                    onServer { client, _ ->
                        val token = client.session().recordToken()
                        val db = client.session()
                        db.authenticate(token)
                        db.use(MAIN, OTHER_DB)

                        db.useDefaults()

                        db.namespace() shouldBe MAIN.value
                        db.database() shouldBe OTHER_DB.value
                    }
                }
            }

            context("Session.use with one name") {
                should("keep the namespace when only the database changes, on the session and on the server") {
                    onServer { _, db ->
                        db.marker() shouldBe "main"

                        db.use(OTHER_DB)

                        db.namespace() shouldBe MAIN.value
                        db.database() shouldBe OTHER_DB.value
                        db.marker() shouldBe "other"
                    }
                }

                should("clear the database when only the namespace changes, so the next statement asks for one") {
                    onServer { _, db ->
                        db.use(MAIN)

                        db.namespace() shouldBe MAIN.value
                        db.database().shouldBeNull()
                        val thrown = shouldThrow<SurrealRpcException> { db.marker() }
                        thrown.message shouldContain "Specify a database"
                    }
                }

                should(
                    "refuse a database with no namespace before the server is asked, because the server would clear the namespace first",
                ) {
                    onServer { client, _ ->
                        val db = client.session()
                        db.signin(Credentials.RootUser("root", "root"))

                        shouldThrow<IllegalStateException> { db.use(MAIN_DB) }

                        db.namespace().shouldBeNull()
                    }
                }

                should("keep working through the credential provider after a one-sided use") {
                    val config =
                        Surreal.Config(
                            url = integrationEndpoint(),
                            autoAuthenticate = true,
                            credentialProvider = { Credentials.RootUser("root", "root") },
                        )
                    onServer(config) { client, _ ->
                        val db = client.session()
                        db.signin(Credentials.RootUser("root", "root"))
                        db.use(MAIN)
                        db.use(OTHER_DB)
                        db.invalidate()

                        db.marker() shouldBe "other"

                        db.namespace() shouldBe MAIN.value
                        db.database() shouldBe OTHER_DB.value
                        db.accessToken().shouldNotBeNull()
                    }
                }
            }

            context("Session.version and Session.ping") {
                should("answer the build string and true before anything is signed in") {
                    runBlocking {
                        val client = Surreal(Surreal.Config(url = integrationEndpoint()))
                        try {
                            val db = client.session()

                            db.version() shouldStartWith "surrealdb-"
                            db.ping() shouldBe true
                        } finally {
                            client.close()
                        }
                    }
                }
            }
        },
    )
