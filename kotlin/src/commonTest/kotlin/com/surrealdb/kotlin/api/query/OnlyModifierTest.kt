package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.RecordIdRange
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import com.surrealdb.kotlin.api.data.get
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val db =
    object : QueryContext {
        override val json: Json = Json

        override suspend fun query(bound: BoundQuery): JsonElement = error("compile-only")
    }

/** Targets that can name at most one record. */
private val oneRecord: List<Target> = listOf(RecordId("person", "alice"), People["alice"])

/** Targets that can name any number of records, including none. */
private val manyRecords: List<Target> =
    listOf(Table("person"), People, RecordIdRange("person", start = "alice", end = "zara"))

private val inferring: List<Pair<String, (Target) -> Query>> =
    listOf(
        "select" to { target -> db.select(target) },
        "update" to { target -> db.update(target) },
        "upsert" to { target -> db.upsert(target) },
        "delete" to { target -> db.delete(target) },
        "merge" to { target -> db.merge(target, JsonObject(emptyMap())) },
        "patch" to { target -> db.patch(target, JsonArray(emptyList())) },
    )

private val forced: List<Pair<String, (Target) -> Query>> =
    listOf(
        "select" to { target -> db.select(target).only() },
        "update" to { target -> db.update(target).only() },
        "upsert" to { target -> db.upsert(target).only() },
        "delete" to { target -> db.delete(target).only() },
        "merge" to { target -> db.merge(target, JsonObject(emptyMap())).only() },
        "patch" to { target -> db.patch(target, JsonArray(emptyList())).only() },
    )

/**
 * `ONLY` asks the server for the record itself rather than a list, and the
 * server rejects the statement the moment a second record matches. Which
 * statements may ask for it is a property of what they point at, so these cases
 * hold every verb to the target's answer.
 */
class OnlyModifierTest :
    ShouldSpec({
        context("a target that names one record") {
            should("carry ONLY in every verb, because no second record can match") {
                for (target in oneRecord) {
                    for ((verb, build) in inferring) {
                        withClue("$verb $target") {
                            build(target).compile().surql shouldContain " ONLY type::record("
                        }
                    }
                }
            }
        }

        context("a target that names a set of records") {
            should("carry no ONLY, because the server rejects the statement on the second match") {
                for (target in manyRecords) {
                    for ((verb, build) in inferring) {
                        withClue("$verb $target") {
                            build(target).compile().surql shouldNotContain "ONLY"
                        }
                    }
                }
            }

            should("still render the target itself unchanged") {
                db.select(People).compile().surql shouldBe "SELECT * FROM type::table(\$_0)"
                db.delete(Table("person")).compile().surql shouldBe "DELETE type::table(\$_0)"
            }
        }

        context("CREATE") {
            should("carry ONLY whatever the target, because it writes exactly one record") {
                for (target in oneRecord + manyRecords) {
                    withClue("create $target") {
                        db.create(target).compile().surql shouldStartWith "CREATE ONLY "
                    }
                }
            }
        }

        context("only()") {
            should("put ONLY back on a target that names a set, in every verb that takes it") {
                for (target in manyRecords) {
                    for ((verb, build) in forced) {
                        withClue("$verb $target") {
                            build(target).compile().surql shouldContain " ONLY "
                        }
                    }
                }
            }

            should("add ONLY and nothing else, so a table target still needs a limit of its own") {
                val plain = db.select(People).compile()
                val single = db.select(People).only().compile()

                single.surql shouldBe plain.surql.replaceFirst("FROM ", "FROM ONLY ")
                single.bindings shouldBe plain.bindings
                single.surql shouldNotContain "LIMIT"
            }

            should("leave a record target rendering exactly as it already did") {
                val inferred = db.update(People["alice"]).compile()
                val asked = db.update(People["alice"]).only().compile()

                asked.surql shouldBe inferred.surql
                asked.bindings shouldBe inferred.bindings
            }
        }
    })
