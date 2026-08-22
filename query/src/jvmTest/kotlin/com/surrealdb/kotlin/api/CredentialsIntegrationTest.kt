package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.api.error.SurrealException
import com.surrealdb.kotlin.api.query.statementResults
import com.surrealdb.kotlin.api.query.surql
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NS_USER = "cr_ns"
private const val NS_PASS = "cr_ns_pass"
private const val DB_USER = "cr_db"
private const val DB_PASS = "cr_db_pass"
private const val ACCESS = "cr_acct"
private const val EMAIL = "ada@example.com"
private const val RECORD_PASS = "cr_record_pass"

private val MAIN = Namespace("main")
private val MAIN_DB = Database("main")

private val FIXTURES =
    """
    REMOVE TABLE IF EXISTS cr_person;
    REMOVE ACCESS IF EXISTS $ACCESS ON DATABASE;
    REMOVE USER IF EXISTS $DB_USER ON DATABASE;
    REMOVE USER IF EXISTS $NS_USER ON NAMESPACE;
    DEFINE USER $NS_USER ON NAMESPACE PASSWORD '$NS_PASS' ROLES EDITOR;
    DEFINE USER $DB_USER ON DATABASE PASSWORD '$DB_PASS' ROLES EDITOR;
    DEFINE ACCESS $ACCESS ON DATABASE TYPE RECORD
        SIGNUP ( CREATE cr_person SET email = ${'$'}email, pass = crypto::argon2::generate(${'$'}pass) )
        SIGNIN ( SELECT * FROM cr_person WHERE email = ${'$'}email AND crypto::argon2::compare(pass, ${'$'}pass) )
        DURATION FOR SESSION 1h;
    """.trimIndent()

private val TEARDOWN =
    """
    REMOVE ACCESS IF EXISTS $ACCESS ON DATABASE;
    REMOVE USER IF EXISTS $DB_USER ON DATABASE;
    REMOVE USER IF EXISTS $NS_USER ON NAMESPACE;
    REMOVE TABLE IF EXISTS cr_person;
    """.trimIndent()

private fun onServer(block: suspend (Session) -> Unit) {
    runBlocking {
        val client = Surreal(Surreal.Config(url = integrationEndpoint()))
        val root = client.session()
        try {
            root.signin(Credentials.RootUser("root", "root"))
            root.use(MAIN, MAIN_DB)
            statementResults(root.query(surql(FIXTURES)))

            block(client.session())
        } finally {
            runCatching { statementResults(root.query(surql(TEARDOWN))) }
            client.close()
        }
    }
}

class CredentialsIntegrationTest :
    ShouldSpec({
        defaultTestConfig = integrationTestConfig

        context("a credential for each level the server defines") {
            should("authenticate a root user from user and pass alone") {
                onServer { db ->
                    db.signin(Credentials.RootUser("root", "root"))

                    db.accessToken().shouldNotBeNull()
                }
            }

            should("authenticate a namespace user from the namespace it was defined in") {
                onServer { db ->
                    db.signin(Credentials.NamespaceUser(MAIN, NS_USER, NS_PASS))

                    db.accessToken().shouldNotBeNull()
                }
            }

            should("authenticate a database user from both names") {
                onServer { db ->
                    db.signin(Credentials.DatabaseUser(MAIN, MAIN_DB, DB_USER, DB_PASS))

                    db.accessToken().shouldNotBeNull()
                }
            }

            should("register a record through an access method and then sign it back in") {
                onServer { db ->
                    val vars =
                        buildJsonObject {
                            put("email", EMAIL)
                            put("pass", RECORD_PASS)
                        }

                    db.signup(Credentials.RecordUser(MAIN, MAIN_DB, ACCESS, vars)).shouldNotBeNull()
                    db.signin(Credentials.RecordUser(MAIN, MAIN_DB, ACCESS, vars))

                    db.accessToken().shouldNotBeNull()
                }
            }
        }

        context("the mistakes the ladder removes, sent through the raw escape so the server can answer them") {
            should("answer a misspelled ns key with the message a wrong password gets") {
                onServer { db ->
                    val misspelled =
                        shouldThrow<SurrealException> {
                            db.signin(
                                Credentials.Raw(
                                    buildJsonObject {
                                        put("user", NS_USER)
                                        put("pass", NS_PASS)
                                        put("namespace", MAIN.value)
                                    },
                                ),
                            )
                        }

                    val wrongPassword =
                        shouldThrow<SurrealException> {
                            db.signin(Credentials.NamespaceUser(MAIN, NS_USER, "not the password"))
                        }

                    misspelled.message shouldContain "There was a problem with authentication"
                    misspelled.message shouldBe wrongPassword.message
                }
            }

            should(
                "answer a namespace user who also sends db with that same message, because the key set picks the level",
            ) {
                onServer { db ->
                    val oneKeyTooMany =
                        shouldThrow<SurrealException> {
                            db.signin(
                                Credentials.Raw(
                                    buildJsonObject {
                                        put("user", NS_USER)
                                        put("pass", NS_PASS)
                                        put("ns", MAIN.value)
                                        put("db", MAIN_DB.value)
                                    },
                                ),
                            )
                        }

                    oneKeyTooMany.message shouldContain "There was a problem with authentication"
                }
            }

            should("answer a namespace that does not exist with that same message, on the system-user path") {
                onServer { db ->
                    val absent =
                        shouldThrow<SurrealException> {
                            db.signin(Credentials.NamespaceUser(Namespace("cr_absent"), NS_USER, NS_PASS))
                        }

                    absent.message shouldContain "There was a problem with authentication"
                }
            }

            should("answer a root user who sends ns with that same message, for the same reason") {
                onServer { db ->
                    val oneKeyTooMany =
                        shouldThrow<SurrealException> {
                            db.signin(
                                Credentials.Raw(
                                    buildJsonObject {
                                        put("user", "root")
                                        put("pass", "root")
                                        put("ns", MAIN.value)
                                    },
                                ),
                            )
                        }

                    oneKeyTooMany.message shouldContain "There was a problem with authentication"
                }
            }

            should("answer record variables nested under a vars key with the message a wrong password gets") {
                onServer { db ->
                    val vars =
                        buildJsonObject {
                            put("email", EMAIL)
                            put("pass", RECORD_PASS)
                        }
                    db.signup(Credentials.RecordUser(MAIN, MAIN_DB, ACCESS, vars))

                    val nested =
                        shouldThrow<SurrealException> {
                            db.signin(
                                Credentials.Raw(
                                    buildJsonObject {
                                        put("ns", MAIN.value)
                                        put("db", MAIN_DB.value)
                                        put("ac", ACCESS)
                                        put("vars", vars)
                                    },
                                ),
                            )
                        }

                    val wrongPassword =
                        shouldThrow<SurrealException> {
                            db.signin(
                                Credentials.RecordUser(
                                    MAIN,
                                    MAIN_DB,
                                    ACCESS,
                                    buildJsonObject {
                                        put("email", EMAIL)
                                        put("pass", "not the password")
                                    },
                                ),
                            )
                        }

                    nested.message shouldContain "No record was returned"
                    nested.message shouldBe wrongPassword.message
                }
            }

            should("answer a sign-up that is not a record access with one message whatever was wrong with it") {
                onServer { db ->
                    val asRoot =
                        shouldThrow<SurrealException> {
                            db.signup(
                                Credentials.Raw(
                                    buildJsonObject {
                                        put("user", "root")
                                        put("pass", "root")
                                    },
                                ),
                            )
                        }

                    val accessKeyMissing =
                        shouldThrow<SurrealException> {
                            db.signup(
                                Credentials.Raw(
                                    buildJsonObject {
                                        put("ns", MAIN.value)
                                        put("db", MAIN_DB.value)
                                        put("email", EMAIL)
                                        put("pass", RECORD_PASS)
                                    },
                                ),
                            )
                        }

                    asRoot.message shouldContain "There was a problem with signing up"
                    asRoot.message shouldBe accessKeyMissing.message
                }
            }
        }

        context("the errors that are already loud, which the ladder leaves alone") {
            should("name an access method that does not exist") {
                onServer { db ->
                    val thrown =
                        shouldThrow<SurrealException> {
                            db.signin(
                                Credentials.RecordUser(
                                    MAIN,
                                    MAIN_DB,
                                    "no_such_access",
                                    buildJsonObject { put("email", EMAIL) },
                                ),
                            )
                        }

                    thrown.message shouldContain "The access method does not exist"
                }
            }

            should("reject a token that is not a JWT") {
                onServer { db ->
                    val thrown = shouldThrow<SurrealException> { db.authenticate("not-a-jwt") }

                    thrown.message shouldContain "InvalidToken"
                }
            }

            should("name a namespace that does not exist on the record path, unlike use, which defines one") {
                onServer { db ->
                    val thrown =
                        shouldThrow<SurrealException> {
                            db.signin(
                                Credentials.RecordUser(
                                    Namespace("cr_absent"),
                                    MAIN_DB,
                                    ACCESS,
                                    buildJsonObject { put("email", EMAIL) },
                                ),
                            )
                        }

                    thrown.message shouldContain "The namespace 'cr_absent' does not exist"
                }
            }
        }

        context("a password the RPC parses as a record id") {
            should("fail rather than sign in, because a bound string becomes a record before anything type-checks it") {
                onServer { db ->
                    val thrown =
                        shouldThrow<SurrealException> {
                            db.signin(Credentials.RootUser("root", "note: remember the milk"))
                        }

                    thrown.message shouldContain "Expected string, got record"
                }
            }
        }

        context("Session.use") {
            should("define a namespace and database that were not there, so a typo points at an empty database") {
                onServer { db ->
                    db.signin(Credentials.RootUser("root", "root"))

                    db.use(Namespace("cr_typo"), Database("cr_typo"))

                    try {
                        db.namespace() shouldBe "cr_typo"
                        statementResults(db.query(surql("INFO FOR ROOT")))
                            .first()
                            .toString() shouldContain "cr_typo"
                    } finally {
                        db.query(surql("REMOVE NAMESPACE IF EXISTS cr_typo"))
                    }
                }
            }
        }
    })
