package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Field
import com.surrealdb.kotlin.api.data.RecordId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive

class SurqlTest :
    ShouldSpec({
        context("surqlTemplate") {
            should("bind interpolated values from left to right, so none reaches the SurrealQL") {
                val name = "Ada Lovelace"
                val age = 36

                val query = surqlTemplate { "RETURN [${bind(name)}, ${bind(age)}]" }

                query.surql shouldBe "RETURN [\$_0, \$_1]"
                query.surql shouldNotContain name
                query.bindings shouldContainExactly
                    mapOf(
                        "_0" to JsonPrimitive(name),
                        "_1" to JsonPrimitive(age),
                    )
            }

            should("keep an unused binding, because SurrealDB accepts extra parameters") {
                val query =
                    surqlTemplate {
                        bind("unused")
                        "RETURN 1"
                    }

                query.surql shouldBe "RETURN 1"
                query.bindings shouldContainExactly mapOf("_0" to JsonPrimitive("unused"))
            }

            should("reject a quoted placeholder, because SurrealDB would match the literal text") {
                for (quote in listOf('"', '\'', '`')) {
                    val error =
                        shouldThrow<IllegalArgumentException> {
                            surqlTemplate { "RETURN $quote${bind(42)}$quote" }
                        }

                    error.message shouldContain "Interpolate bind(...) without quotes"
                }
            }

            should("reject a placeholder joined to a word, because SurrealDB reads one longer parameter name") {
                val error =
                    shouldThrow<IllegalArgumentException> {
                        surqlTemplate { "RETURN ${bind(42)}days" }
                    }

                error.message shouldContain "reads a different parameter name"
            }

            should("distinguish a two-digit parameter from the shorter name at its start") {
                val query =
                    surqlTemplate {
                        val placeholders = (0..10).map { bind(it) }
                        "RETURN [${placeholders.joinToString()}]"
                    }

                query.bindings.size shouldBe 11
                query.surql shouldContain "\$_10"
            }

            should("ignore quote marks in comments when checking a later placeholder") {
                val query =
                    surqlTemplate {
                        "-- caller's value\nRETURN ${bind(42)}"
                    }

                query.surql shouldBe "-- caller's value\nRETURN \$_0"
            }
        }

        context("BoundQuery") {
            should("give consecutive binds distinct names") {
                val query = BoundQuery()

                query.appendLiteral("a=")
                query.bind(JsonPrimitive(1))
                query.appendLiteral(", b=")
                query.bind(JsonPrimitive(2))

                query.bindings shouldContainExactly
                    mapOf(
                        "_0" to JsonPrimitive(1),
                        "_1" to JsonPrimitive(2),
                    )
            }

            should("render both halves of a record id as bindings") {
                val query = BoundQuery()

                query.appendValue(RecordId("user", "alice"))

                query.surql shouldBe "type::record(\$_0, \$_1)"
                query.bindings shouldContainExactly
                    mapOf(
                        "_0" to JsonPrimitive("user"),
                        "_1" to JsonPrimitive("alice"),
                    )
            }
        }

        context("toJson") {
            should("reject a target, because JSON transport would send it as an ordinary object") {
                val target = RecordId("person", "alice")

                val failure = shouldThrow<IllegalArgumentException> { toJson(target) }

                failure.message shouldContain target.toString()
            }
        }

        context("Condition.toSurql") {
            should("keep the parentheses around both sides of a conjunction") {
                val query = with(People) { (age greater 18) and (active eq true) }.toSurql()

                query.surql shouldContain " AND "
                query.surql.first() shouldBe '('
                query.surql.last() shouldBe ')'
            }
        }

        context("Field") {
            should("reject SurrealQL in a declared path") {
                shouldThrow<IllegalArgumentException> {
                    Field<String>("name; DROP TABLE x")
                }
            }
        }
    })
