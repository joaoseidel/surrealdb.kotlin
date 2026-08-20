package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.get
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldContainValue
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val compileOnly =
    object : QueryContext {
        override val json: Json = Json

        override suspend fun query(bound: BoundQuery): JsonElement = error("compile-only")
    }

class AssignmentTest :
    ShouldSpec({
        context("set") {
            should("render one assignment per field, in the order they were written") {
                val compiled =
                    compileOnly
                        .update(People)
                        .set {
                            it[name] = "Alice"
                            it[age] = 30
                        }.compile()

                compiled.surql shouldBe "UPDATE type::table(\$_0) SET name = \$_1, age = \$_2"
            }

            should("bind every value, so nothing a caller supplied reaches the SurrealQL") {
                val compiled =
                    compileOnly
                        .update(People)
                        .set {
                            it[name] = "Robert'); DROP TABLE person; --"
                            it[active] = true
                        }.compile()

                compiled.surql shouldNotContain "DROP TABLE"
                compiled.bindings shouldContainValue JsonPrimitive("Robert'); DROP TABLE person; --")
                compiled.bindings shouldContainValue JsonPrimitive(true)
            }

            should("write the field path and nothing else into the SurrealQL") {
                compileOnly
                    .update(People)
                    .set { it[firstName] = "Alice" }
                    .compile()
                    .surql shouldContain " SET first_name = \$"
            }

            should("emit a dotted path for a nested field, because SET builds the missing parent") {
                compileOnly
                    .update(People)
                    .set { it[address[Postal::city]] = "Boston" }
                    .compile()
                    .surql shouldContain " SET address.city = \$"
            }

            should("emit no clause at all when the block assigned nothing, because empty SET is a parse error") {
                val compiled = compileOnly.update(People).set { }.compile()

                compiled.surql shouldBe "UPDATE type::table(\$_0)"
                compiled.bindings shouldHaveSize 1
            }

            should("work on create, which has no WHERE to follow it") {
                compileOnly
                    .create(People)
                    .set { it[age] = 30 }
                    .compile()
                    .surql shouldBe "CREATE ONLY type::table(\$_0) SET age = \$_1"
            }

            should("work on upsert") {
                compileOnly
                    .upsert(People)
                    .set { it[age] = 30 }
                    .compile()
                    .surql shouldBe "UPSERT type::table(\$_0) SET age = \$_1"
            }

            should("sit before WHERE and RETURN, because SurrealQL takes the clauses in that order") {
                val surql =
                    compileOnly
                        .update(People)
                        .set { it[age] = 0 }
                        .where { age greater 30 }
                        .returnMode(ReturnMode.After)
                        .compile()
                        .surql

                surql shouldBe
                    "UPDATE type::table(\$_0) SET age = \$_1 WHERE (age > \$_2) RETURN AFTER"
            }

            should("apply to one record when the target carries the schema") {
                compileOnly
                    .update(People["alice"])
                    .set { it[age] = 30 }
                    .compile()
                    .surql shouldBe "UPDATE ONLY type::record(\$_0, \$_1) SET age = \$_2"
            }
        }

        context("the single data slot") {
            should("drop a CONTENT when set follows it, because the two clauses cannot both parse") {
                val compiled =
                    compileOnly
                        .update(People)
                        .content(buildJsonObject { put("name", "Alice") })
                        .set { it[age] = 30 }
                        .compile()

                compiled.surql shouldNotContain "CONTENT"
                compiled.surql shouldContain " SET age = \$"
            }

            should("drop assignments when content follows them") {
                val compiled =
                    compileOnly
                        .update(People)
                        .set { it[age] = 30 }
                        .content(buildJsonObject { put("name", "Alice") })
                        .compile()

                compiled.surql shouldNotContain " SET "
                compiled.surql shouldContain " CONTENT \$"
            }

            should("leave the statement with no data clause when an empty set follows a content") {
                compileOnly
                    .update(People)
                    .content(buildJsonObject { put("name", "Alice") })
                    .set { }
                    .compile()
                    .surql shouldBe "UPDATE type::table(\$_0)"
            }
        }

        context("a value that names a record") {
            should("write the link rather than binding an object, so the server stores a reference") {
                val compiled =
                    compileOnly
                        .update(Notes)
                        .set { it[author] = RecordId("person", "alice") }
                        .compile()

                compiled.surql shouldBe
                    "UPDATE type::table(\$_0) SET author = type::record(\$_1, \$_2)"
            }

            should("take a schema-carrying record through its RecordId, which reads the same") {
                compileOnly
                    .update(Notes)
                    .set { it[author] = People["alice"].record }
                    .compile()
                    .surql shouldBe
                    "UPDATE type::table(\$_0) SET author = type::record(\$_1, \$_2)"
            }
        }

        context("a composite value") {
            should("encode through the context serializer, so a field need not carry one") {
                val compiled =
                    compileOnly
                        .update(People)
                        .set { it[address] = Postal("Boston", "02110") }
                        .compile()

                compiled.surql shouldBe "UPDATE type::table(\$_0) SET address = \$_1"
                compiled.bindings["_1"].toString() shouldBe "{\"city\":\"Boston\",\"postal_code\":\"02110\"}"
            }

            should("replace the whole object, which is what SET on an object field does") {
                compileOnly
                    .update(People)
                    .set { it[address] = Postal("Boston", "02110") }
                    .compile()
                    .surql shouldNotContain "address.city"
            }
        }

        context("the array operators") {
            should("append with SurrealQL's own +=, which builds the array when the field is absent") {
                val compiled =
                    compileOnly
                        .update(People)
                        .set { it[tags] += "cs" }
                        .compile()

                compiled.surql shouldBe "UPDATE type::table(\$_0) SET tags += \$_1"
                compiled.bindings shouldContainValue JsonPrimitive("cs")
            }

            should("remove with -=") {
                compileOnly
                    .update(People)
                    .set { it[tags] -= "cs" }
                    .compile()
                    .surql shouldBe "UPDATE type::table(\$_0) SET tags -= \$_1"
            }

            should("mix with plain assignments in one statement, in the order written") {
                compileOnly
                    .update(People)
                    .set {
                        it[age] = 30
                        it[tags] += "cs"
                        it[tags] -= "lisp"
                    }.compile()
                    .surql shouldBe
                    "UPDATE type::table(\$_0) SET age = \$_1, tags += \$_2, tags -= \$_3"
            }
        }

        context("a list value") {
            should("bind as an array, so a whole list can replace a field") {
                val compiled =
                    compileOnly
                        .update(People)
                        .set { it[tags] = listOf("cs", "lisp") }
                        .compile()

                compiled.surql shouldBe "UPDATE type::table(\$_0) SET tags = \$_1"
                compiled.bindings["_1"].toString() shouldBe "[\"cs\",\"lisp\"]"
            }
        }
    })
