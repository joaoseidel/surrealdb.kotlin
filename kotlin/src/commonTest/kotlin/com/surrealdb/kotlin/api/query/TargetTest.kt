package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.RecordIdRange
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
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
        "select" to { target -> SelectQuery(compileOnly, target) },
        "create" to { target -> CreateQuery(compileOnly, target) },
        "upsert" to { target -> UpsertQuery(compileOnly, target) },
        "update" to { target -> UpdateQuery(compileOnly, target) },
        "delete" to { target -> DeleteQuery(compileOnly, target) },
        "merge" to { target -> MergeQuery(compileOnly, target, JsonObject(emptyMap())) },
        "patch" to { target -> PatchQuery(compileOnly, target, JsonArray(emptyList()), diff = false) },
        "relate" to { target -> RelateQuery(compileOnly, target, Table("likes"), RecordId("person", "b")) },
    )

class TargetTest :
    ShouldSpec({
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

                    for ((verb, build) in verbs) {
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
    })
