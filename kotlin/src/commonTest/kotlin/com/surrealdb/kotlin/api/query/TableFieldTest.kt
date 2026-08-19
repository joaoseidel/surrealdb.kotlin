package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Field
import com.surrealdb.kotlin.api.data.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A `WHERE` on a field the record does not have returns an empty result, not an
 * error — so an unchecked field name is a query that silently matches nothing.
 * These cases hold the declaration to the record type's serial descriptor.
 */
class TableFieldTest :
    ShouldSpec({
        context("Table.field") {
            should("throw when the name is not a field of the record type, naming what is") {
                val thrown =
                    shouldThrow<IllegalArgumentException> {
                        object : Table<Human>("person", Human.serializer()) {
                            val typo = field<Int>("pagse")
                        }
                    }

                thrown.message shouldContain "'pagse' is not a field of Human"
                thrown.message shouldContain "age"
            }

            should("accept the serial name of a renamed property, because that is what the wire carries") {
                People.firstName.path shouldBe "first_name"
            }

            should("reject the Kotlin name of a renamed property, which is the failure a name check cannot see") {
                val thrown =
                    shouldThrow<IllegalArgumentException> {
                        object : Table<Human>("person", Human.serializer()) {
                            val kotlinName = field<String>("firstName")
                        }
                    }

                thrown.message shouldContain "'firstName' is not a field of Human"
                thrown.message shouldContain "first_name"
            }

            should("descend a dotted path into a nested record") {
                val declared =
                    object : Table<Human>("person", Human.serializer()) {
                        val city = field<String>("address.city")
                    }

                declared.city.path shouldBe "address.city"
            }

            should("validate every segment of a dotted path, not only the first") {
                val thrown =
                    shouldThrow<IllegalArgumentException> {
                        object : Table<Human>("person", Human.serializer()) {
                            val wrong = field<String>("address.zip")
                        }
                    }

                thrown.message shouldContain "'zip' is not a field of Postal"
                thrown.message shouldContain "postal_code"
            }

            should("descend an index into the element type of a list") {
                val declared =
                    object : Table<Human>("person", Human.serializer()) {
                        val firstTag = field<String>("tags[0]")
                    }

                declared.firstTag.path shouldBe "tags[0]"
            }

            should("accept a segment under a shape it cannot see through, rather than inventing a failure") {
                val declared =
                    object : Table<Human>("person", Human.serializer()) {
                        val anything = field<String>("meta.whatever")
                    }

                declared.anything.path shouldBe "meta.whatever"
            }

            should("take the field name from the property when the delegate form is used") {
                People.age.path shouldBe "age"
            }

            should("reject a path that is not an identifier, which is the one thing bound parameters cannot cover") {
                shouldThrow<IllegalArgumentException> { Field<String>("name; DROP TABLE x") }
            }
        }

        context("Table.recordId") {
            should("not be checked against the descriptor, because the server has an id either way") {
                People.id.path shouldBe "id"
            }
        }

        context("Table.nested") {
            should("resolve a property reference to the qualified path") {
                People.address[Postal::city].path shouldBe "address.city"
            }

            should("throw on a property serialized under another name, naming the property") {
                val thrown = shouldThrow<IllegalArgumentException> { People.address[Postal::zip] }

                thrown.message shouldContain "zip"
                thrown.message shouldContain "postal_code"
            }
        }
    })
