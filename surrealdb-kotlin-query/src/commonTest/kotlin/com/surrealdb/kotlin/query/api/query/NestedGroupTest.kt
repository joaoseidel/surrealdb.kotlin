package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.Row
import com.surrealdb.kotlin.query.api.data.Nested
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class NestedGroupTest :
    ShouldSpec(
        {
            context("a field declared in a group") {
                should("carry the group's path, so the declaration names the leaf and nothing else") {
                    Contacts.address.city.path.value shouldBe "address.city"
                }

                should("carry it around an explicit name too, which is how a renamed field is declared") {
                    Contacts.address.zip.path.value shouldBe "address.postal_code"
                }

                should("carry every group above it, because a path stopping short reads a field that does not exist") {
                    Contacts.address.geo.lat.path.value shouldBe "address.geo.lat"
                }
            }

            context("what a table reports as declared") {
                should("hold the fields of its groups, so the schema check sees a nested field like any other") {
                    Contacts.declaredFields.map { it.path.value } shouldBe
                        listOf("name", "address.city", "address.postal_code", "address.geo.lat", "address.geo.lon")
                }
            }

            context("a group whose path does not belong where it is declared") {
                should("be rejected at declaration, because the query it would build silently matches nothing") {
                    val thrown =
                        shouldThrow<IllegalArgumentException> {
                            object : Nested("address") {
                                val geo = nested(Detached)
                            }
                        }

                    thrown.message shouldContain "expected 'geo' to start with 'address.'"
                }

                should("be rejected when the path is not an identifier, which parameters cannot cover") {
                    shouldThrow<IllegalArgumentException> { Nested("address; DROP TABLE x") }
                }
            }

            context("a nested field in a statement") {
                should("render as the dotted path, which is what SurrealQL names a field of an object with") {
                    val compiled = RecordingContext().select(Contacts).where { address.geo.lat greater 40.0 }.compile()

                    compiled.surql shouldContain "WHERE (address.geo.lat > "
                }
            }

            context("a nested field in a result") {
                should("read through the object the server rebuilt, rather than a flattened key") {
                    val row = Row.fromJson(Json, Json.parseToJsonElement(NESTED_RECORD) as JsonObject)

                    row[Contacts.address.city] shouldBe "Boston"
                    row[Contacts.address.geo.lat] shouldBe 42.36
                }
            }
        },
    ) {
    private companion object {
        const val NESTED_RECORD = """{"address":{"city":"Boston","geo":{"lat":42.36,"lon":-71.06}}}"""
    }
}

private object Detached : Nested("geo") {
    val lat by field<Double>()
}
