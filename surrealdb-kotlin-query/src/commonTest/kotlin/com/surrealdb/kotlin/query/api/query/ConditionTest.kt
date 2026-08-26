package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.Row
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class ConditionTest :
    ShouldSpec(
        {
            context("comparison operators") {
                should("render each operator with its operand bound, never interpolated") {
                    val cases =
                        listOf(
                            with(People) { age eq 30 } to "(age = \$_0)",
                            with(People) { age neq 30 } to "(age != \$_0)",
                            with(People) { age greater 18 } to "(age > \$_0)",
                            with(People) { age greaterEq 18 } to "(age >= \$_0)",
                            with(People) { age less 18 } to "(age < \$_0)",
                            with(People) { age lessEq 18 } to "(age <= \$_0)",
                        )

                    cases.forEach { (condition, expected) ->
                        val compiled = condition.toSurQL()

                        compiled.surql shouldBe expected
                        compiled.bindings shouldHaveSize 1
                    }
                }

                should("bind the operand rather than writing it into the SurrealQL") {
                    val compiled = with(People) { email eq "x@y.z" }.toSurQL()

                    compiled.surql shouldNotContain "x@y.z"
                    compiled.bindings.values.first() shouldBe JsonPrimitive("x@y.z")
                }

                should("compare two fields without binding either, because neither is a value") {
                    val compiled = with(People) { name eq firstName }.toSurQL()

                    compiled.surql shouldBe "(name = first_name)"
                    compiled.bindings shouldHaveSize 0
                }
            }

            context("collection operators") {
                should("bind a collection as one parameter") {
                    val compiled = with(People) { age inside listOf(18, 30) }.toSurQL()

                    compiled.surql shouldBe "(age IN \$_0)"
                    compiled.bindings shouldHaveSize 1
                }

                should("render the CONTAINS family against a list field") {
                    with(People) { tags contains "kotlin" }.toSurQL().surql shouldBe "(tags CONTAINS \$_0)"
                    with(People) { tags containsAll listOf("a") }.toSurQL().surql shouldBe "(tags CONTAINSALL \$_0)"
                    with(People) { tags containsAny listOf("a") }.toSurQL().surql shouldBe "(tags CONTAINSANY \$_0)"
                }
            }

            context("string operators") {
                should("render as a SurrealQL function call with the pattern bound") {
                    val compiled = with(People) { name startsWith "Ada" }.toSurQL()

                    compiled.surql shouldBe "string::starts_with(name, \$_0)"
                    compiled.bindings shouldHaveSize 1
                }
            }

            context("presence tests") {
                should("distinguish NONE, NULL and present, because SurrealDB does") {
                    with(People) { email.isNone() }.toSurQL().surql shouldBe "(email IS NONE)"
                    with(People) { email.isNull() }.toSurQL().surql shouldBe "(email IS NULL)"
                    with(People) { email.exists() }.toSurQL().surql shouldBe "(email IS NOT NONE)"
                }
            }

            context("composition") {
                should("join a conjunction with AND and keep each operand parenthesised") {
                    val compiled = with(People) { (age greater 18) and (active eq true) }.toSurQL()

                    compiled.surql shouldBe "((age > \$_0) AND (active = \$_1))"
                    compiled.bindings shouldHaveSize 2
                }

                should("flatten a chain of the same operator rather than nesting it") {
                    val compiled = with(People) { (age greater 18) and (active eq true) and (name eq "Ada") }.toSurQL()

                    compiled.surql shouldBe "((age > \$_0) AND (active = \$_1) AND (name = \$_2))"
                }

                should("join a disjunction with OR") {
                    with(People) { (age greater 18) or (active eq true) }
                        .toSurQL()
                        .surql shouldBe "((age > \$_0) OR (active = \$_1))"
                }

                should("negate with a leading bang") {
                    with(People) { not(active eq true) }.toSurQL().surql shouldBe "!(active = \$_0)"
                }
            }

            context("the n-ary groups") {
                should("render all as a parenthesised conjunction, so no binding order applies") {
                    with(People) { all(age greater 18, active eq true) }
                        .toSurQL()
                        .surql shouldBe "((age > \$_0) AND (active = \$_1))"
                }

                should("render any as a parenthesised disjunction") {
                    with(People) { any(age greater 18, active eq true) }
                        .toSurQL()
                        .surql shouldBe "((age > \$_0) OR (active = \$_1))"
                }

                should("render none as the negation of a disjunction") {
                    with(People) { none(age greater 18, active eq true) }
                        .toSurQL()
                        .surql shouldBe "!((age > \$_0) OR (active = \$_1))"
                }

                should("keep a group intact when it is chained, rather than flattening it away") {
                    with(People) { any(age greater 18, active eq true) and (name eq "Ada") }
                        .toSurQL()
                        .surql shouldBe "(((age > \$_0) OR (active = \$_1)) AND (name = \$_2))"
                }

                should("reject an empty group, which would render as an empty clause") {
                    shouldThrow<IllegalArgumentException> { with(People) { all() } }
                    shouldThrow<IllegalArgumentException> { with(People) { any() } }
                    shouldThrow<IllegalArgumentException> { with(People) { none() } }
                }
            }

            context("the raw escape hatch") {
                should("carry its bindings through, so an interpolated value is still a parameter") {
                    val compiled =
                        with(People) {
                            raw {
                                "geo::distance(location, ${bind("point")}) < ${bind(10)}"
                            }
                        }.toSurQL()

                    compiled.surql shouldBe "(geo::distance(location, \$_0) < \$_1)"
                    compiled.bindings shouldHaveSize 2
                }
            }

            context("a raw fragment spliced into a statement") {
                should("keep the statement's own bindings, because both mint parameters from zero") {
                    val compileOnly =
                        object : QueryContext {
                            override val json: Json = Json

                            override suspend fun queryValues(bound: BoundQuery): List<Row> = error("compile-only")
                        }

                    val compiled =
                        compileOnly
                            .select(People)
                            .where {
                                raw {
                                    "stock > ${bind(7)}"
                                }
                            }.compile()

                    compiled.bindings shouldHaveSize 2
                    compiled.bindings.values shouldContain JsonPrimitive("person")
                    compiled.bindings.values shouldContain JsonPrimitive(7)
                }
            }

            context("a nested field") {
                should("render as the dotted path it resolved to") {
                    with(People) { city eq "Cambridge" }
                        .toSurQL()
                        .surql shouldBe "(address.city = \$_0)"
                }
            }

            context("a RecordId comparison in a where clause") {
                should("bind both table and id as separate parameters") {
                    val compiled = with(People) { id eq RecordId("user", "alice") }.toSurQL()

                    compiled.surql shouldContain "type::record("
                    compiled.bindings shouldHaveSize 2
                    compiled.bindings.values shouldContain JsonPrimitive("user")
                    compiled.bindings.values shouldContain JsonPrimitive("alice")
                }

                should("merge correctly when spliced into a select statement") {
                    val compileOnly =
                        object : QueryContext {
                            override val json: Json = Json

                            override suspend fun queryValues(bound: BoundQuery): List<Row> = error("compile-only")
                        }

                    val compiled =
                        compileOnly
                            .select(People)
                            .where { People.id eq RecordId("user", "haglmvhrwaau7rtrtt99") }
                            .compile()

                    compiled.bindings shouldHaveSize 3
                    compiled.bindings.values shouldContain JsonPrimitive("person")
                    compiled.bindings.values shouldContain JsonPrimitive("user")
                    compiled.bindings.values shouldContain JsonPrimitive("haglmvhrwaau7rtrtt99")
                    compiled.surql shouldContain "type::record("
                }
            }
        },
    )
