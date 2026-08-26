package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.query.api.data.Field
import com.surrealdb.kotlin.query.api.data.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class TableFieldTest :
    ShouldSpec(
        {
            context("Table.field") {
                should("take the field name from the property when the delegate form is used") {
                    People.age.path.value shouldBe "age"
                }

                should("keep a name given explicitly, which is how a renamed field is declared") {
                    People.firstName.path.value shouldBe "first_name"
                }

                should("accept a dotted path, which is how a field of a nested object is named") {
                    People.city.path.value shouldBe "address.city"
                }

                should("accept an index, which is how one element of an array is named") {
                    val declared =
                        object : Table("person") {
                            val firstTag = field<String>("tags[0]")
                        }

                    declared.firstTag.path.value shouldBe "tags[0]"
                }

                should(
                    "reject a path that is not an identifier, which is the one thing bound parameters cannot cover",
                ) {
                    shouldThrow<IllegalArgumentException> { Field<String>("name; DROP TABLE x") }
                }
            }

            context("Table.recordId") {
                should("name the field SurrealDB fixes the id at") {
                    People.id.path.value shouldBe "id"
                }
            }

            context("what a table reports as declared") {
                should("hold every declared field, because that list is what the schema check puts to the server") {
                    Posts.declaredFields.map { it.path.value } shouldBe listOf("author", "comments")
                }

                should("leave the record id out, because the server has one whether or not a schema names it") {
                    People.declaredFields.map { it.path.value } shouldBe
                        listOf("name", "age", "email", "active", "tags", "first_name", "address", "address.city")
                }
            }
        },
    )
