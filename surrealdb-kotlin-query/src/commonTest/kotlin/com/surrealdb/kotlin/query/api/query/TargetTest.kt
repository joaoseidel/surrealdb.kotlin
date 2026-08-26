package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.RecordIdRange
import com.surrealdb.kotlin.core.api.data.Target
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val compileOnly =
    object : QueryContext {
        override val json: Json = Json

        override suspend fun query(bound: BoundQuery): JsonElement = error("compile-only")
    }

/**
 * Every statement that names what it operates on takes a [Target] and renders
 * it through one shared renderer. These cases hold each verb to that: a target
 * means the same thing whichever statement points at it.
 */
private val verbs: List<Pair<String, (Target) -> Query>> =
    listOf(
        "select" to { target -> compileOnly.select(target) },
        "create" to { target -> compileOnly.create(target) },
        "upsert" to { target -> compileOnly.upsert(target) },
        "update" to { target -> compileOnly.update(target) },
        "delete" to { target -> compileOnly.delete(target) },
        "merge" to { target -> compileOnly.merge(target, JsonObject(emptyMap())) },
        "patch" to { target -> compileOnly.patch(target, JsonArray(emptyList())) },
        "relate" to { target -> compileOnly.relate(target, Table("likes"), RecordId("person", "b")) },
    )

private val rangeVerbs: List<Pair<String, (Target) -> Query>> = verbs.filterNot { it.first == "relate" }

class TargetTest :
    ShouldSpec(
        {
            context("a RecordIdRange in a RELATE position") {
                should("be rejected while building, because the server cannot parse a range there") {
                    val range = RecordIdRange("person", "a", "z")

                    val failure =
                        shouldThrow<IllegalArgumentException> {
                            compileOnly.relate(range, Table("likes"), RecordId("person", "b")).compile()
                        }

                    shouldThrow<IllegalArgumentException> {
                        compileOnly.relate(RecordId("person", "a"), range, RecordId("person", "b")).compile()
                    }

                    failure.message shouldContain range.toString()
                }
            }

            context("a relation table name") {
                should("escape punctuation, so every valid table name remains reachable") {
                    val query =
                        compileOnly
                            .relate(RecordId("person", "a"), Table("best-friends"), RecordId("person", "b"))
                            .compile()

                    query.surql shouldContain "->`best-friends`->"
                }
            }

            context("a Target given to a statement") {
                should("render the same in every verb, because one renderer serves them all") {
                    val targets =
                        listOf(
                            Table("person"),
                            RecordId("person", "alice"),
                            RecordIdRange("person", start = "alice", end = "zara"),
                        )

                    for (target in targets) {
                        val rendered = BoundQuery().appendTarget(target).surql
                        val applicable = if (target is RecordIdRange) rangeVerbs else verbs

                        for ((verb, build) in applicable) {
                            withClue("$verb should name $target as `$rendered`") {
                                build(target).compile().surql shouldContain rendered
                            }
                        }
                    }
                }

                should("bind a table or record name rather than write it into the SurrealQL") {
                    for (target in listOf(Table("person"), RecordId("person", "alice"))) {
                        for ((verb, build) in verbs) {
                            withClue("$verb should not inline $target") {
                                build(target).compile().surql shouldNotContain "person"
                            }
                        }
                    }
                }
            }
        },
    )
