package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.Session
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.RecordIdRange
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.get
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class Slot(
    val n: Int,
)

private object Slots : Table<Slot>("rt_slot", Slot.serializer()) {
    val n by field<Int>()
}

private fun keysOf(rows: JsonElement): List<String> =
    rows.jsonArray.map {
        it.jsonObject["id"]!!
            .jsonPrimitive.content
            .substringAfter(':')
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
            db.query("DEFINE TABLE ${Slots.tableName} SCHEMALESS")
            db.query("DELETE ${Slots.tableName}")
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
                    updated.jsonArray.map { it.jsonObject["n"]!!.jsonPrimitive.int } shouldBe listOf(7, 7)

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
