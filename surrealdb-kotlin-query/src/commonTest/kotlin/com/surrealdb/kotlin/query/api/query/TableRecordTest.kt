package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.get
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldContainValue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val compileOnly =
    object : QueryContext {
        override val json: Json = Json

        override suspend fun query(bound: BoundQuery): JsonElement = error("compile-only")
    }

/**
 * Every verb that takes a schema, called on one record of a declared table.
 * Each entry has to resolve to the record-scoped overload to compile at all,
 * which is half of what these cases assert.
 */
private val typedVerbs: List<Pair<String, () -> Query>> =
    listOf(
        "select" to { compileOnly.select(People["alice"]) },
        "create" to { compileOnly.create(People["alice"]) },
        "upsert" to { compileOnly.upsert(People["alice"]) },
        "update" to { compileOnly.update(People["alice"]) },
        "delete" to { compileOnly.delete(People["alice"]) },
        "merge" to { compileOnly.merge(People["alice"], JsonObject(emptyMap())) },
        "patch" to { compileOnly.patch(People["alice"], JsonArray(emptyList())) },
    )

class TableRecordTest :
    ShouldSpec({
        context("Table.get") {
            should("name a record of that table, taking the table name from the schema") {
                People["alice"].toString() shouldBe "person:alice"
            }

            should("equal another handle on the same record, so builders stay comparable") {
                People["alice"] shouldBe People["alice"]
                People["alice"] shouldNotBe People["zara"]
            }
        }

        context("a TableRecord given to a statement") {
            should("render exactly as the bare RecordId does, because both reach one renderer") {
                val expected = BoundQuery().appendTarget(RecordId("person", "alice")).surql

                for ((verb, build) in typedVerbs) {
                    withClue("$verb should name person:alice as `$expected`") {
                        build().compile().surql shouldContain expected
                    }
                }
            }

            should("bind the table and id rather than write them into the SurrealQL") {
                for ((verb, build) in typedVerbs) {
                    withClue("$verb should not inline person:alice") {
                        val compiled = build().compile()

                        compiled.surql shouldNotContain "person"
                        compiled.surql shouldNotContain "alice"
                        compiled.bindings shouldContainValue JsonPrimitive("alice")
                    }
                }
            }

            should("keep the schema, so a WHERE over one record still names fields") {
                compileOnly
                    .update(People["alice"])
                    .where { age greater 30 }
                    .compile()
                    .surql shouldContain "(age > "
            }

            should("keep the schema through create, which carried none before") {
                compileOnly
                    .create(People["alice"])
                    .returnMode(ReturnMode.Fields(listOf(People.name)))
                    .compile()
                    .surql shouldContain " RETURN name"
            }
        }

        context("a TableRecord in a value position") {
            should("write the link, so a record can be handed to a field") {
                val fragment = BoundQuery().appendValue(People["alice"])

                fragment.surql shouldBe "type::record(\$_0, \$_1)"
                fragment.bindings shouldBe
                    mapOf("_0" to JsonPrimitive("person"), "_1" to JsonPrimitive("alice"))
            }
        }

        context("an undeclared table") {
            should("still yield a record handle, because Table(name) is a Table too") {
                BoundQuery().appendTarget(Table("person")["alice"]).surql shouldBe "type::record(\$_0, \$_1)"
            }
        }

        context("a TableRecord in a RELATE position") {
            should("render as the record it names, because RELATE parenthesises either the same way") {
                val viaRecord =
                    compileOnly
                        .relate(People["alice"], Table("likes"), People["zara"])
                        .compile()

                val viaRecordId =
                    compileOnly
                        .relate(RecordId("person", "alice"), Table("likes"), RecordId("person", "zara"))
                        .compile()

                viaRecord.surql shouldBe viaRecordId.surql
                viaRecord.bindings shouldBe viaRecordId.bindings
            }
        }
    })
