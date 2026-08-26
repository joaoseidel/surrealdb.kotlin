package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.error.SurrealRpcException
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.get
import com.surrealdb.kotlin.query.api.integrationEndpoint
import com.surrealdb.kotlin.query.api.integrationTestConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking

private object Lines : Table("om_line") {
    val title by field<String>()
    val n by field<Int>()
}

private fun onServer(block: suspend (Session) -> Unit) {
    runBlocking {
        val client = Surreal(Surreal.Config(url = integrationEndpoint()))
        val db = client.session()
        try {
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            db.query(surql("DEFINE TABLE ${Lines.tableName} SCHEMALESS"))
            db.query(surql("DELETE ${Lines.tableName}"))
            db
                .create(Lines["a"])
                .set {
                    it[title] = "One"
                    it[n] = 1
                }.await()
            db
                .create(Lines["b"])
                .set {
                    it[title] = "Two"
                    it[n] = 2
                }.await()

            block(db)
        } finally {
            client.close()
        }
    }
}

class OnlyModifierIntegrationTest :
    ShouldSpec(
        {
            defaultTestConfig = integrationTestConfig

            context("a select over a table holding two records") {
                should("return both of them") {
                    onServer { db ->
                        val rows = db.select(Lines).await()

                        rows.map { it[Lines.title] } shouldBe listOf("One", "Two")
                    }
                }

                should("return both when a where clause matches both") {
                    onServer { db ->
                        val rows =
                            db
                                .select(Lines)
                                .where { n greaterEq 1 }
                                .await()

                        rows.size shouldBe 2
                    }
                }
            }

            context("an update over a table holding two records") {
                should("write to both and answer with every record it touched, whatever the match count") {
                    onServer { db ->
                        val updated =
                            db
                                .update(Lines)
                                .set { it[n] = 9 }
                                .await()

                        updated.map { it[Lines.n] } shouldBe listOf(9, 9)
                    }
                }
            }

            context("a record target") {
                should("answer with the record itself rather than a list of one, which is what ONLY buys") {
                    onServer { db ->
                        val statement = db.select(Lines["a"])

                        db
                            .query(statement.compile())
                            .single()
                            .shouldBeInstanceOf<com.surrealdb.kotlin.core.api.data.Row>()
                        statement.awaitSingleOrNull()?.get(Lines.title) shouldBe "One"
                    }
                }

                should("answer with null for a record that does not exist, rather than failing") {
                    onServer { db ->
                        val statement = db.select(Lines["nobody"])

                        db.query(statement.compile()) shouldBe emptyList()
                        statement.awaitSingleOrNull() shouldBe null
                        statement.await() shouldBe emptyList()
                    }
                }
            }

            context("only() on a table target") {
                should("fail on the second matching record, because that is what ONLY means") {
                    onServer { db ->
                        val failure = shouldThrow<SurrealRpcException> { db.select(Lines).only().await() }

                        failure.message.shouldContain("ONLY")
                    }
                }

                should("answer with the record itself once a limit narrows it to one") {
                    onServer { db ->
                        val statement =
                            db
                                .select(Lines)
                                .limit(1)
                                .only()

                        db
                            .query(statement.compile())
                            .single()
                            .shouldBeInstanceOf<com.surrealdb.kotlin.core.api.data.Row>()
                        statement.awaitSingleOrNull()?.get(Lines.title) shouldBe "One"
                    }
                }
            }

            context("a table target without only()") {
                should("answer with a list, which is the shape the terminals read every result through") {
                    onServer { db ->
                        db.query(db.select(Lines).compile()).shouldBeInstanceOf<List<*>>()
                    }
                }
            }

            context("a create over a table") {
                should("answer with the record it wrote rather than a list of one") {
                    onServer { db ->
                        val created = db.create(Lines).set { it[title] = "Three" }.awaitSingleOrNull()

                        created?.get(Lines.title) shouldBe "Three"
                    }
                }
            }
        },
    )
