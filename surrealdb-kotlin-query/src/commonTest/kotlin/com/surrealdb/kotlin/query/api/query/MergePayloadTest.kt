package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.query.api.data.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private object Accounts : Table("account") {
    val name by field<String>()
    val age by field<Int>()
    val tags by field<List<String>>()
    val firstTag = field<String>("tags[0]")
    val everyTag = field<List<String>>("tags[*]")
    val address = field<String>("address")
    val city = field<String>("address.city")
    val zip = field<String>("address.postal_code")
    val lat = field<Double>("address.geo.lat")
    val lng = field<Double>("address.geo.lng")
    val author = field<RecordId>("author")
}

private fun payloadOf(build: Accounts.(MergePayload) -> Unit): JsonObject =
    RecordingContext()
        .merge(Accounts, build)
        .compile()
        .bindings
        .getValue("_1")
        .jsonObject

class MergePayloadTest :
    ShouldSpec(
        {
            context("a merge payload") {
                should("fold a dotted path into a nested object, because the server reads a payload key as a key") {
                    payloadOf { it[city] = "Boston" }.toString() shouldBe """{"address":{"city":"Boston"}}"""
                }

                should("fold two fields of one object into that one object, so neither drops the other") {
                    payloadOf {
                        it[city] = "Boston"
                        it[zip] = "02110"
                    }.toString() shouldBe """{"address":{"city":"Boston","postal_code":"02110"}}"""
                }

                should("fold at any depth, because MERGE merges the objects it is given at every level") {
                    payloadOf {
                        it[city] = "Boston"
                        it[lat] = 42.36
                        it[lng] = -71.06
                    }.toString() shouldBe """{"address":{"city":"Boston","geo":{"lat":42.36,"lng":-71.06}}}"""
                }

                should("keep the keys in the order the block named them") {
                    payloadOf {
                        it[age] = 30
                        it[name] = "Ada"
                    }.keys.toList() shouldBe listOf("age", "name")
                }

                should("encode each value by the type its field declared") {
                    payloadOf { it[age] = 30 }.toString() shouldBe """{"age":30}"""
                }

                should("encode a link as the record id string the server reads back as a link") {
                    payloadOf { it[author] = RecordId("writer", "ada") }.toString() shouldBe
                        """{"author":"writer:ada"}"""
                }

                should("send an empty object for a block that names nothing, which the server answers untouched") {
                    payloadOf { }.toString() shouldBe "{}"
                }

                should("bind the payload, so nothing a caller supplied reaches the SurrealQL") {
                    val injected = "Robert'); DROP TABLE account; --"
                    val compiled = RecordingContext().merge(Accounts) { it[name] = injected }.compile()

                    compiled.surql shouldBe "UPDATE type::table(\$_0) MERGE \$_1"
                }

                should("refuse an array element, because folding one would replace the array with an object") {
                    val failure = shouldThrow<IllegalArgumentException> { payloadOf { it[firstTag] = "cs" } }

                    failure.message.toString() shouldContain "cannot name an array element ('tags[0]')"
                }

                should("refuse a path naming every element, for the same reason") {
                    val failure = shouldThrow<IllegalArgumentException> { payloadOf { it[everyTag] = listOf("cs") } }

                    failure.message.toString() shouldContain "('tags[*]')"
                }

                should("refuse two fields where one names the other, because a payload holds one value per key") {
                    val failure =
                        shouldThrow<IllegalArgumentException> {
                            payloadOf {
                                it[address] = "Boston"
                                it[city] = "Boston"
                            }
                        }

                    failure.message.toString() shouldContain "'address' and 'address.city' cannot both be merged"
                }

                should("refuse the same field twice, because the second would silently drop the first") {
                    val failure =
                        shouldThrow<IllegalArgumentException> {
                            payloadOf {
                                it[name] = "Ada"
                                it[name] = "Grace"
                            }
                        }

                    failure.message.toString() shouldContain "'name' and 'name' cannot both be merged"
                }
            }
        },
    )
