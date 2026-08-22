package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.Credentials
import com.surrealdb.kotlin.api.Database
import com.surrealdb.kotlin.api.Namespace
import com.surrealdb.kotlin.api.Session
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.RecordIdRange
import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.get
import com.surrealdb.kotlin.api.integrationEndpoint
import com.surrealdb.kotlin.api.integrationTestConfig
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private object Slots : Table("rt_slot") {
    val id = recordId()
    val n by field<Int>()
}

private fun keysOf(rows: List<Row>): List<String> = rows.map { it[Slots.id].id }

private fun onServer(block: suspend (Session) -> Unit) {
    runBlocking {
        val client = Surreal(Surreal.Config(url = integrationEndpoint()))
        val db = client.session()
        try {
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            db.query(surql("DEFINE TABLE ${Slots.tableName} SCHEMALESS"))
            db.query(surql("DELETE ${Slots.tableName}"))
            listOf("a" to 1, "m" to 2, "z" to 3).forEach { (key, value) ->
                db.create(Slots[key]).set { it[n] = value }.await()
            }

            block(db)
        } finally {
            client.close()
        }
    }
}

class RangeTargetIntegrationTest :
    ShouldSpec({
        defaultTestConfig = integrationTestConfig

        context("a select over a record-id range") {
            should("return the records inside it, leaving the end key out") {
                onServer { db ->
                    val rows = db.select(range("a", "z")).await()

                    keysOf(rows) shouldBe listOf("a", "m")
                }
            }

            should("include the end key when the range says so") {
                onServer { db ->
                    val rows = db.select(range("a", "z", includeEnd = true)).await()

                    keysOf(rows) shouldBe listOf("a", "m", "z")
                }
            }

            should("run open at either end, and open at both") {
                onServer { db ->
                    keysOf(db.select(range(start = "m")).await()) shouldBe listOf("m", "z")
                    keysOf(db.select(range(end = "m")).await()) shouldBe listOf("a")
                    keysOf(db.select(range()).await()) shouldBe listOf("a", "m", "z")
                }
            }
        }

        context("a write over a record-id range") {
            should("touch the records inside it and no others") {
                onServer { db ->
                    val updated =
                        db
                            .update(range("a", "z"))
                            .content(buildJsonObject { put("n", JsonPrimitive(7)) })
                            .await()

                    keysOf(updated) shouldBe listOf("a", "m")
                    updated.map { it[Slots.n] } shouldBe listOf(7, 7)

                    db.delete(range("a", "z")).await()

                    keysOf(db.select(Slots).await()) shouldBe listOf("z")
                }
            }
        }
    })

private fun range(
    start: String? = null,
    end: String? = null,
    includeEnd: Boolean = false,
) = RecordIdRange(Slots.tableName, start = start, end = end, includeEnd = includeEnd)
