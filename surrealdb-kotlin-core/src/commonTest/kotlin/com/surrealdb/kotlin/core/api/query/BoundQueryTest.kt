package com.surrealdb.kotlin.core.api.query

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

class BoundQueryTest :
    ShouldSpec(
        {
            context("appendFragment") {
                should("not rename when there is no collision") {
                    val q = BoundQuery()
                    q.bind(JsonPrimitive("alice"))
                    q.appendFragment("age > \$_0", mapOf("_0" to JsonPrimitive(30)))

                    q.surql shouldBe "\$_0age > \$_1"
                    q.bindings shouldHaveSize 2
                    q.bindings["_0"] shouldBe JsonPrimitive("alice")
                    q.bindings["_1"] shouldBe JsonPrimitive(30)
                }

                should("rename a single colliding parameter") {
                    val q = BoundQuery()
                    q.bind(JsonPrimitive("user"))
                    q.appendFragment("(id = \$_0)", mapOf("_0" to JsonPrimitive("alice")))

                    q.surql shouldBe "\$_0(id = \$_1)"
                    q.bindings shouldHaveSize 2
                    q.bindings["_0"] shouldBe JsonPrimitive("user")
                    q.bindings["_1"] shouldBe JsonPrimitive("alice")
                }

                should("rename two colliding parameters without cascading") {
                    val q = BoundQuery()
                    q.bind(JsonPrimitive("user"))
                    q.appendFragment(
                        "type::record(\$_0, \$_1)",
                        mapOf(
                            "_0" to JsonPrimitive("user"),
                            "_1" to JsonPrimitive("alice"),
                        ),
                    )

                    q.surql shouldBe "\$_0type::record(\$_1, \$_2)"
                    q.bindings shouldHaveSize 3
                    q.bindings["_0"] shouldBe JsonPrimitive("user")
                    q.bindings["_1"] shouldBe JsonPrimitive("user")
                    q.bindings["_2"] shouldBe JsonPrimitive("alice")
                }

                should("rename only colliding parameters, leaving non-colliding ones intact") {
                    val q = BoundQuery()
                    q.bind(JsonPrimitive("user"))
                    q.appendFragment(
                        "(\$_0 = \$_1 AND age > \$_2)",
                        mapOf(
                            "_0" to JsonPrimitive("name"),
                            "_1" to JsonPrimitive("alice"),
                            "_2" to JsonPrimitive(30),
                        ),
                    )

                    q.surql shouldBe "\$_0(\$_1 = \$_2 AND age > \$_3)"
                    q.bindings shouldHaveSize 4
                    q.bindings["_0"] shouldBe JsonPrimitive("user")
                    q.bindings["_1"] shouldBe JsonPrimitive("name")
                    q.bindings["_2"] shouldBe JsonPrimitive("alice")
                    q.bindings["_3"] shouldBe JsonPrimitive(30)
                }

                should("handle the exact select+where+RecordId scenario from the bug report") {
                    val q = BoundQuery()
                    q.bind(JsonPrimitive("user"))
                    q.appendFragment(
                        "(id = type::record(\$_0, \$_1))",
                        mapOf(
                            "_0" to JsonPrimitive("user"),
                            "_1" to JsonPrimitive("haglmvhrwaau7rtrtt99"),
                        ),
                    )

                    q.surql shouldBe "\$_0(id = type::record(\$_1, \$_2))"
                    q.bindings shouldHaveSize 3
                    q.bindings["_0"] shouldBe JsonPrimitive("user")
                    q.bindings["_1"] shouldBe JsonPrimitive("user")
                    q.bindings["_2"] shouldBe JsonPrimitive("haglmvhrwaau7rtrtt99")
                }

                should("handle multiple appendFragment calls with cascading collisions") {
                    val q = BoundQuery()
                    q.bind(JsonPrimitive("user"))
                    q.appendFragment("(id = \$_0)", mapOf("_0" to JsonPrimitive("alice")))
                    q.appendFragment(" AND name = \$_0", mapOf("_0" to JsonPrimitive("bob")))

                    q.surql shouldBe "\$_0(id = \$_1) AND name = \$_2"
                    q.bindings shouldHaveSize 3
                    q.bindings["_0"] shouldBe JsonPrimitive("user")
                    q.bindings["_1"] shouldBe JsonPrimitive("alice")
                    q.bindings["_2"] shouldBe JsonPrimitive("bob")
                }

                should("handle append() which delegates to appendFragment") {
                    val other = BoundQuery()
                    other.bind(JsonPrimitive("user"))
                    other.bind(JsonPrimitive("alice"))

                    val q = BoundQuery()
                    q.bind(JsonPrimitive("person"))

                    q.append(other)

                    q.surql shouldBe "\$_0\$_1\$_2"
                    q.bindings shouldHaveSize 3
                    q.bindings["_0"] shouldBe JsonPrimitive("person")
                    q.bindings["_1"] shouldBe JsonPrimitive("user")
                    q.bindings["_2"] shouldBe JsonPrimitive("alice")
                }
            }
        },
    )
