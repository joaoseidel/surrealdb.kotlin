package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.data.Row
import com.surrealdb.kotlin.query.api.data.Nested
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.get
import com.surrealdb.kotlin.query.api.integrationEndpoint
import com.surrealdb.kotlin.query.api.integrationTestConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private object Profiles : Table("ng_profile") {
    val name by field<String>()

    object Address : Nested("address") {
        val city by field<String>()
        val zip = field<String>("postal_code")

        object Geo : Nested("address.geo") {
            val lat by field<Double>()
        }

        val geo = nested(Geo)
    }

    val address = nested(Address)
}

private object LeafOnly : Table("ng_leaf") {
    object Address : Nested("address") {
        object Geo : Nested("address.geo") {
            val lat by field<Double>()
        }

        val geo = nested(Geo)
    }

    val address = nested(Address)
}

private suspend fun Query.theRecord(): Row = awaitSingleOrNull() ?: error("the statement answered with no record")

/**
 * Every nested field is `option<...>`, because a SCHEMAFULL object field a
 * record does not carry is rejected with "Expected `object` but found `NONE`",
 * and these records each write one branch of the object.
 */
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
                        REMOVE TABLE IF EXISTS ${Profiles.tableName};
                        REMOVE TABLE IF EXISTS ${LeafOnly.tableName};
                        DEFINE TABLE ${Profiles.tableName} SCHEMAFULL;
                        DEFINE FIELD name ON ${Profiles.tableName} TYPE option<string>;
                        DEFINE FIELD address ON ${Profiles.tableName} TYPE object;
                        DEFINE FIELD address.city ON ${Profiles.tableName} TYPE option<string>;
                        DEFINE FIELD address.postal_code ON ${Profiles.tableName} TYPE option<string>;
                        DEFINE FIELD address.geo ON ${Profiles.tableName} TYPE option<object>;
                        DEFINE FIELD address.geo.lat ON ${Profiles.tableName} TYPE option<float>;
                        DEFINE TABLE ${LeafOnly.tableName} SCHEMAFULL;
                        DEFINE FIELD address.geo.lat ON ${LeafOnly.tableName} TYPE option<float>;
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

class NestedGroupIntegrationTest :
    ShouldSpec({
        defaultTestConfig = integrationTestConfig

        context("a nested field in a SET") {
            should("write two levels down, building both objects above it") {
                onServer { db ->
                    val written =
                        db
                            .create(Profiles["ada"])
                            .set {
                                it[name] = "Ada"
                                it[address.city] = "Boston"
                                it[address.zip] = "02110"
                                it[address.geo.lat] = 42.36
                            }.theRecord()

                    written[Profiles.address.city] shouldBe "Boston"
                    written[Profiles.address.zip] shouldBe "02110"
                    written[Profiles.address.geo.lat] shouldBe 42.36
                }
            }
        }

        context("a nested field in a WHERE") {
            should("match on the path, at either level") {
                onServer { db ->
                    db.create(Profiles["grace"]).set { it[address.city] = "Boston" }.await()
                    db.create(Profiles["alan"]).set { it[address.geo.lat] = 51.5 }.await()

                    db.select(Profiles).where { address.city eq "Boston" }.await() shouldHaveSize 1
                    db.select(Profiles).where { address.geo.lat greater 50.0 }.await() shouldHaveSize 1
                }
            }
        }

        context("checkSchema over a table with groups") {
            should("report nothing when the server defines every nested path") {
                onServer { db ->
                    db.checkSchema(Profiles).shouldBeEmpty()
                }
            }

            should("report nothing for a leaf the server defines alone, because a group declares leaves") {
                onServer { db ->
                    db.create(LeafOnly["one"]).set { it[address.geo.lat] = 1.5 }.await()

                    db.checkSchema(LeafOnly).shouldBeEmpty()
                }
            }
        }
    })
