package com.surrealdb.kotlin.api.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class ConditionTest :
    ShouldSpec({
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
                    val compiled = condition.toSurql()

                    compiled.surql shouldBe expected
                    compiled.bindings shouldHaveSize 1
                }
            }

            should("bind the operand rather than writing it into the SurrealQL") {
                val compiled = with(People) { email eq "x@y.z" }.toSurql()

                compiled.surql shouldNotContain "x@y.z"
                compiled.bindings.values.first() shouldBe JsonPrimitive("x@y.z")
            }

            should("compare two fields without binding either, because neither is a value") {
                val compiled = with(People) { name eq firstName }.toSurql()

                compiled.surql shouldBe "(name = first_name)"
                compiled.bindings shouldHaveSize 0
            }
        }

        context("collection operators") {
            should("bind a collection as one parameter") {
                val compiled = with(People) { age inside listOf(18, 30) }.toSurql()

                compiled.surql shouldBe "(age IN \$_0)"
                compiled.bindings shouldHaveSize 1
            }

            should("render the CONTAINS family against a list field") {
                with(People) { tags contains "kotlin" }.toSurql().surql shouldBe "(tags CONTAINS \$_0)"
                with(People) { tags containsAll listOf("a") }.toSurql().surql shouldBe "(tags CONTAINSALL \$_0)"
                with(People) { tags containsAny listOf("a") }.toSurql().surql shouldBe "(tags CONTAINSANY \$_0)"
            }
        }

        context("string operators") {
            should("render as a SurrealQL function call with the pattern bound") {
                val compiled = with(People) { name startsWith "Ada" }.toSurql()

                compiled.surql shouldBe "string::starts_with(name, \$_0)"
                compiled.bindings shouldHaveSize 1
            }
        }

        context("presence tests") {
            should("distinguish NONE, NULL and present, because SurrealDB does") {
                with(People) { email.isNone() }.toSurql().surql shouldBe "(email IS NONE)"
                with(People) { email.isNull() }.toSurql().surql shouldBe "(email IS NULL)"
                with(People) { email.exists() }.toSurql().surql shouldBe "(email IS NOT NONE)"
            }
        }

        context("composition") {
            should("join a conjunction with AND and keep each operand parenthesised") {
                val compiled = with(People) { (age greater 18) and (active eq true) }.toSurql()

                compiled.surql shouldBe "((age > \$_0) AND (active = \$_1))"
                compiled.bindings shouldHaveSize 2
            }

            should("flatten a chain of the same operator rather than nesting it") {
                val compiled = with(People) { (age greater 18) and (active eq true) and (name eq "Ada") }.toSurql()

                compiled.surql shouldBe "((age > \$_0) AND (active = \$_1) AND (name = \$_2))"
            }

            should("join a disjunction with OR") {
                with(People) { (age greater 18) or (active eq true) }
                    .toSurql()
                    .surql shouldBe "((age > \$_0) OR (active = \$_1))"
            }

            should("negate with a leading bang") {
                with(People) { not(active eq true) }.toSurql().surql shouldBe "!(active = \$_0)"
            }
        }

        context("the n-ary groups") {
            should("render all as a parenthesised conjunction, so no binding order applies") {
                with(People) { all(age greater 18, active eq true) }
                    .toSurql()
                    .surql shouldBe "((age > \$_0) AND (active = \$_1))"
            }

            should("render any as a parenthesised disjunction") {
                with(People) { any(age greater 18, active eq true) }
                    .toSurql()
                    .surql shouldBe "((age > \$_0) OR (active = \$_1))"
            }

            should("render none as the negation of a disjunction") {
                with(People) { none(age greater 18, active eq true) }
                    .toSurql()
                    .surql shouldBe "!((age > \$_0) OR (active = \$_1))"
            }

            should("keep a group intact when it is chained, rather than flattening it away") {
                with(People) { any(age greater 18, active eq true) and (name eq "Ada") }
                    .toSurql()
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
                            +"geo::distance(location, "
                            value("point")
                            +") < "
                            value(10)
                        }
                    }.toSurql()

                compiled.surql shouldBe "(geo::distance(location, \$_0) < \$_1)"
                compiled.bindings shouldHaveSize 2
            }
        }

        context("a raw fragment spliced into a statement") {
            should("keep the statement's own bindings, because both mint parameters from zero") {
                val compileOnly =
                    object : QueryContext {
                        override val json: Json = Json

                        override suspend fun query(bound: BoundQuery): JsonElement = error("compile-only")
                    }

                val compiled =
                    compileOnly
                        .select(People)
                        .where {
                            raw {
                                +"stock > "
                                value(7)
                            }
                        }.compile()

                compiled.bindings shouldHaveSize 2
                compiled.bindings.values shouldContain JsonPrimitive("person")
                compiled.bindings.values shouldContain JsonPrimitive(7)
            }
        }

        context("a nested field") {
            should("render as the dotted path it resolved to") {
                with(People) { address[Postal::city] eq "Cambridge" }
                    .toSurql()
                    .surql shouldBe "(address.city = \$_0)"
            }
        }
    })
