package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.Row
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Field
import com.surrealdb.kotlin.query.api.data.get
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val compileOnly =
    object : QueryContext {
        override val json: Json = Json

        override suspend fun queryValues(bound: BoundQuery): List<Row> = error("compile-only")
    }

class ContentTest :
    ShouldSpec(
        {
            context("create with content DSL") {
                should("bind the content object with all assigned fields") {
                    val compiled =
                        compileOnly
                            .create(People)
                            .content {
                                it[name] = "Alice"
                                it[age] = 30
                            }.compile()

                    compiled.surql shouldBe "CREATE ONLY type::table(\$_0) CONTENT \$_1"
                    compiled.bindings["_0"] shouldBe JsonPrimitive("person")
                    val data = compiled.bindings["_1"] as JsonObject
                    data["name"] shouldBe JsonPrimitive("Alice")
                    data["age"] shouldBe JsonPrimitive(30)
                }

                should("work on a specific record target") {
                    val compiled =
                        compileOnly
                            .create(People["alice"])
                            .content {
                                it[age] = 30
                            }.compile()

                    compiled.surql shouldBe "CREATE ONLY type::record(\$_0, \$_1) CONTENT \$_2"
                    compiled.bindings["_0"] shouldBe JsonPrimitive("person")
                    compiled.bindings["_1"] shouldBe JsonPrimitive("alice")
                    val data = compiled.bindings["_2"] as JsonObject
                    data["age"] shouldBe JsonPrimitive(30)
                }

                should("emit no CONTENT clause when the block assigned nothing") {
                    val compiled = compileOnly.create(People).content { }.compile()

                    compiled.surql shouldBe "CREATE ONLY type::table(\$_0)"
                    compiled.bindings shouldHaveSize 1
                }
            }

            context("insert with content DSL") {
                should("compile to INSERT INTO with bound table and data object") {
                    val compiled =
                        compileOnly
                            .insert(People)
                            .content {
                                it[name] = "Alice"
                                it[age] = 30
                            }.compile()

                    compiled.surql shouldBe "INSERT INTO \$_0 \$_1"
                    compiled.bindings["_0"] shouldBe JsonPrimitive("person")
                    val data = compiled.bindings["_1"] as JsonObject
                    data["name"] shouldBe JsonPrimitive("Alice")
                    data["age"] shouldBe JsonPrimitive(30)
                }

                should("refuse to compile without a payload, because SurrealDB has no bare INSERT") {
                    val error =
                        shouldThrow<IllegalArgumentException> {
                            compileOnly.insert(People).content { }.compile()
                        }

                    error.message shouldContain "has nothing to insert"
                }

                should("work directly with block on query context insert extension") {
                    val compiled =
                        compileOnly
                            .insert(People) {
                                it[name] = "Bob"
                                it[age] = 25
                            }.compile()

                    compiled.surql shouldBe "INSERT INTO \$_0 \$_1"
                    compiled.bindings["_0"] shouldBe JsonPrimitive("person")
                    val data = compiled.bindings["_1"] as JsonObject
                    data["name"] shouldBe JsonPrimitive("Bob")
                    data["age"] shouldBe JsonPrimitive(25)
                }
            }

            context("insertRelation with content DSL") {
                should("compile to INSERT RELATION INTO with bound table and data object") {
                    val compiled =
                        compileOnly
                            .insertRelation(Notes)
                            .content {
                                it[body] = "Hello"
                            }.compile()

                    compiled.surql shouldBe "INSERT RELATION INTO \$_0 \$_1"
                    compiled.bindings["_0"] shouldBe JsonPrimitive("note")
                    val data = compiled.bindings["_1"] as JsonObject
                    data["body"] shouldBe JsonPrimitive("Hello")
                }

                should("work directly with block on insertRelation extension") {
                    val compiled =
                        compileOnly
                            .insertRelation(Notes) {
                                it[body] = "Hello"
                            }.compile()

                    compiled.surql shouldBe "INSERT RELATION INTO \$_0 \$_1"
                }
            }

            context("update and upsert with content DSL") {
                should("work on update") {
                    val compiled =
                        compileOnly
                            .update(People)
                            .content {
                                it[name] = "Alice"
                            }.compile()

                    compiled.surql shouldBe "UPDATE type::table(\$_0) CONTENT \$_1"
                }

                should("work on upsert") {
                    val compiled =
                        compileOnly
                            .upsert(People)
                            .content {
                                it[name] = "Alice"
                            }.compile()

                    compiled.surql shouldBe "UPSERT type::table(\$_0) CONTENT \$_1"
                }
            }

            context("nested fields folding") {
                should("fold dotted paths into nested objects") {
                    val compiled =
                        compileOnly
                            .create(Contacts)
                            .content {
                                it[name] = "Alice"
                                it[address.city] = "Boston"
                                it[address.zip] = "02110"
                            }.compile()

                    val data = compiled.bindings["_1"] as JsonObject
                    data["name"] shouldBe JsonPrimitive("Alice")
                    val addr = data["address"] as JsonObject
                    addr["city"] shouldBe JsonPrimitive("Boston")
                    addr["postal_code"] shouldBe JsonPrimitive("02110")
                }

                should("fold deeply nested paths") {
                    val compiled =
                        compileOnly
                            .create(Contacts)
                            .content {
                                it[address.geo.lat] = 42.36
                                it[address.geo.lon] = -71.06
                            }.compile()

                    val data = compiled.bindings["_1"] as JsonObject
                    val addr = data["address"] as JsonObject
                    val geo = addr["geo"] as JsonObject
                    geo["lat"] shouldBe JsonPrimitive(42.36)
                    geo["lon"] shouldBe JsonPrimitive(-71.06)
                }
            }

            context("switching between set and content") {
                should("drop set when content follows it") {
                    val compiled =
                        compileOnly
                            .create(People)
                            .set { it[age] = 30 }
                            .content { it[name] = "Alice" }
                            .compile()

                    compiled.surql shouldNotContain "SET"
                    compiled.surql shouldContain "CONTENT"
                }

                should("drop content when set follows it") {
                    val compiled =
                        compileOnly
                            .create(People)
                            .content { it[name] = "Alice" }
                            .set { it[age] = 30 }
                            .compile()

                    compiled.surql shouldNotContain "CONTENT"
                    compiled.surql shouldContain "SET"
                }

                should("leave no clause when an empty content follows a set") {
                    val compiled =
                        compileOnly
                            .create(People)
                            .set { it[age] = 30 }
                            .content { }
                            .compile()

                    compiled.surql shouldBe "CREATE ONLY type::table(\$_0)"
                }
            }

            context("link and composite values") {
                should("encode RecordId as a string link") {
                    val compiled =
                        compileOnly
                            .create(Notes)
                            .content {
                                it[body] = "Meeting notes"
                                it[author] = RecordId("person", "alice")
                            }.compile()

                    val data = compiled.bindings["_1"] as JsonObject
                    data["author"] shouldBe JsonPrimitive("person:alice")
                }

                should("encode composite objects through serializer") {
                    val compiled =
                        compileOnly
                            .create(People)
                            .content {
                                it[address] = Postal("Boston", "02110")
                            }.compile()

                    val data = compiled.bindings["_1"] as JsonObject
                    val addr = data["address"] as JsonObject
                    addr["city"] shouldBe JsonPrimitive("Boston")
                    addr["postal_code"] shouldBe JsonPrimitive("02110")
                }
            }

            context("validation") {
                should("reject array indexing in content paths") {
                    val invalidField = Field<String>("tags[0]")
                    val error =
                        shouldThrow<IllegalArgumentException> {
                            buildContentPayload(Json, People) {
                                it[invalidField] = "first"
                            }
                        }

                    error.message shouldContain "A content payload cannot name an array element"
                }

                should("reject overlapping nested paths") {
                    val addressField = Field<Postal>("address")
                    val cityField = Field<String>("address.city")
                    val error =
                        shouldThrow<IllegalArgumentException> {
                            buildContentPayload(Json, People) {
                                it[addressField] = Postal("Boston", "02110")
                                it[cityField] = "Cambridge"
                            }
                        }

                    error.message shouldContain "cannot both be set"
                }
            }
        },
    )
