package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.RecordIdRange
import com.surrealdb.kotlin.core.api.data.RecordKey
import com.surrealdb.kotlin.core.api.data.Row
import com.surrealdb.kotlin.core.api.data.Target
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.uuid.Uuid

private val compileOnly =
    object : QueryContext {
        override val json: Json = Json

        override suspend fun queryValues(bound: BoundQuery): List<Row> = error("compile-only")
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

private val uuid = Uuid.parse("0196a3c2-1234-7abc-8def-0123456789ab")

private val rangeVerbs: List<Pair<String, (Target) -> Query>> = verbs.filterNot { it.first == "relate" }

class TargetTest :
    ShouldSpec(
        {
            context("a RecordIdRange in a RELATE position") {
                should("be rejected while building, because the server cannot parse a range there") {
                    val range = RecordIdRange("person", RecordKey.Text("a"), RecordKey.Text("z"))

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

            context("a RecordId of each kind of key") {
                should("cast a text key to a string, because the server reads a bound 'a:b' as the key b") {
                    val q = BoundQuery().appendTarget(RecordId("person", "a:b"))

                    q.surql shouldBe "type::record(\$_0, (<string> \$_1))"
                    q.bindings shouldBe mapOf("_0" to JsonPrimitive("person"), "_1" to JsonPrimitive("a:b"))
                }

                should("bind an integer key as a number, which JSON carries as itself") {
                    val q = BoundQuery().appendTarget(RecordId("person", 1))

                    q.surql shouldBe "type::record(\$_0, \$_1)"
                    q.bindings["_1"] shouldBe JsonPrimitive(1L)
                }

                should("cast a uuid key, so text that is not a uuid fails rather than becoming a string key") {
                    val q = BoundQuery().appendTarget(RecordId("person", uuid))

                    q.surql shouldBe "type::record(\$_0, (<uuid> \$_1))"
                    q.bindings["_1"] shouldBe JsonPrimitive(uuid.toString())
                }

                should("bind an array or object key as JSON, which the server reads as that kind of key") {
                    val array =
                        buildJsonArray {
                            add("a")
                            add(1)
                        }
                    val obj = buildJsonObject { put("a", JsonPrimitive(1)) }

                    val viaArray = BoundQuery().appendTarget(RecordId("person", RecordKey.Array(array)))
                    val viaObject = BoundQuery().appendTarget(RecordId("person", RecordKey.Object(obj)))

                    viaArray.surql shouldBe "type::record(\$_0, \$_1)"
                    viaArray.bindings["_1"] shouldBe array
                    viaObject.surql shouldBe "type::record(\$_0, \$_1)"
                    viaObject.bindings["_1"] shouldBe obj
                }

                should("render the same in a where clause as in a target position, because both reach one renderer") {
                    val expected =
                        listOf(
                            RecordId("person", "a:b") to "type::record(\$_1, (<string> \$_2))",
                            RecordId("person", 1) to "type::record(\$_1, \$_2)",
                            RecordId("person", uuid) to "type::record(\$_1, (<uuid> \$_2))",
                        )

                    for ((id, spelling) in expected) {
                        val inWhere =
                            compileOnly
                                .select(People)
                                .where { People.id eq id }
                                .compile()
                                .surql

                        withClue("$id in a where clause") { inWhere shouldContain spelling }
                    }
                }
            }

            context("a Target given to a statement") {
                should("render the same in every verb, because one renderer serves them all") {
                    val targets =
                        listOf(
                            Table("person"),
                            RecordId("person", "alice"),
                            RecordIdRange("person", start = RecordKey.Text("alice"), end = RecordKey.Text("zara")),
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
