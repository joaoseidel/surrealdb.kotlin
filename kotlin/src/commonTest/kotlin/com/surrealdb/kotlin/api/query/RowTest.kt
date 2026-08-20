package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private object Sightings : Table("sighting") {
    val id = recordId()
    val name by field<String>()
    val nickname by field<String?>()
    val tags by field<List<String>>()
    val firstTag = field<String>("tags[0]")
    val everyTag = field<List<String>>("tags[*]")
    val authorNames = field<List<String?>>("authors[*].name")
    val address = field<Postal>("address")
    val city = field<String>("address.city")
    val zip = field<String>("address.postal_code")
}

private fun row(body: String): Row = Row(Json, Json.parseToJsonElement(body) as JsonObject)

/**
 * Reading a record without declaring a type for it.
 *
 * Every shape here is one SurrealDB v3.2.4 answers with: a record id as
 * `sighting:alice`, a nested object rebuilt rather than flattened, and a field
 * that was never set left out of the record entirely.
 */
class RowTest :
    ShouldSpec({
        context("a field the record carries") {
            should("come back as the type the field was declared with") {
                val sighting = row("""{"name":"Ada","tags":["cs","lisp"]}""")

                sighting[Sightings.name] shouldBe "Ada"
                sighting[Sightings.tags] shouldBe listOf("cs", "lisp")
            }

            should("come back as a record id for the id, which is the field every record has") {
                row("""{"id":"sighting:alice"}""")[Sightings.id] shouldBe RecordId("sighting", "alice")
            }

            should("resolve a dotted path, because SurrealDB rebuilds the nesting rather than flattening it") {
                val sighting = row("""{"address":{"city":"Boston","postal_code":"02110"}}""")

                sighting[Sightings.city] shouldBe "Boston"
                sighting[Sightings.zip] shouldBe "02110"
            }

            should("read the whole nested object through the field declared beside the paths into it") {
                row("""{"address":{"city":"Boston","postal_code":"02110"}}""")[Sightings.address] shouldBe
                    Postal("Boston", "02110")
            }

            should("index into an array") {
                row("""{"tags":["cs","lisp"]}""")[Sightings.firstTag] shouldBe "cs"
            }

            should("read every element of an array through [*]") {
                row("""{"tags":["cs","lisp"]}""")[Sightings.everyTag] shouldBe listOf("cs", "lisp")
            }

            should("read a field of every element, holding a null where an element has none") {
                val sighting = row("""{"authors":[{"name":"Ada"},{"nick":"x"}]}""")

                sighting[Sightings.authorNames] shouldBe listOf("Ada", null)
            }
        }

        context("a field the record does not carry") {
            should("say which field and what the record does hold, rather than failing inside the decoder") {
                val failure = shouldThrow<NoSuchElementException> { row("""{"name":"Ada"}""")[Sightings.city] }

                failure.message.shouldContain("address.city")
                failure.message.shouldContain("[name]")
            }

            should("read as null when the field's type is nullable, because SurrealDB leaves a NONE out") {
                row("""{"name":"Ada"}""")[Sightings.nickname] shouldBe null
            }

            should("read an explicit null as null too, since both mean the same to a caller") {
                row("""{"nickname":null}""")[Sightings.nickname] shouldBe null
            }

            should("read [*] as absent when the record has no array to walk") {
                shouldThrow<NoSuchElementException> { row("""{"name":"Ada"}""")[Sightings.everyTag] }
            }
        }

        context("whether the record carries a field at all") {
            should("tell an absent field from one the server sent as null") {
                val sighting = row("""{"nickname":null}""")

                (Sightings.nickname in sighting) shouldBe true
                (Sightings.name in sighting) shouldBe false
            }
        }

        context("a field whose type is not known at the call site") {
            should("decode through a serializer passed in, because reified refuses a Field<*>") {
                val projection: List<com.surrealdb.kotlin.api.data.Field<*>> = listOf(Sightings.name)

                row("""{"name":"Ada"}""").decode(projection.single(), String.serializer()) shouldBe "Ada"
            }
        }

        context("the record as it arrived") {
            should("stay reachable, so a field nothing declared is still readable") {
                row("""{"unmodelled":1}""").content.keys shouldBe setOf("unmodelled")
            }
        }
    })
