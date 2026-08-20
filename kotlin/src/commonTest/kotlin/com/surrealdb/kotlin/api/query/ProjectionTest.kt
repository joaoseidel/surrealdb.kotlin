package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private object Agenda : Table("agenda") {
    val title by field<String>()
    val firstTag = field<String>("tags[0]")
    val secondTag = field<String>("tags[1]")
    val everyTag = field<List<String>>("tags[*]")
    val city = field<String>("venue.city")
}

private fun row(body: String): Row = Row(Json, Json.parseToJsonElement(body) as JsonObject)

/**
 * What a projection compiles to, and where the value it asks for arrives.
 *
 * Every alias here is one SurrealDB v3.2.4 was probed with. `SELECT tags[0]`
 * answers `{"tags": "cs"}` and `SELECT tags[0], tags[1]` answers
 * `{"tags": "lisp"}`, so an index that is not aliased is at best in the wrong
 * place and at worst the wrong value.
 */
class ProjectionTest :
    ShouldSpec({
        context("a projection over a plain path") {
            should("name the fields and nothing more, because the server rebuilds the nesting itself") {
                val compiled = RecordingContext().select(Agenda).fields(Agenda.title, Agenda.city).compile()

                compiled.surql shouldContain "SELECT title, venue.city FROM"
            }
        }

        context("a projection over an indexed path") {
            should("alias the path to itself, because the server otherwise answers on the parent key") {
                val compiled = RecordingContext().select(Agenda).fields(Agenda.firstTag).compile()

                compiled.surql shouldContain "SELECT tags[0] AS `tags[0]` FROM"
            }

            should("alias each index separately, because two of them would otherwise land on one key") {
                val compiled = RecordingContext().select(Agenda).fields(Agenda.firstTag, Agenda.secondTag).compile()

                compiled.surql shouldContain "SELECT tags[0] AS `tags[0]`, tags[1] AS `tags[1]` FROM"
            }

            should("alias every element the same way") {
                val compiled = RecordingContext().select(Agenda).fields(Agenda.everyTag).compile()

                compiled.surql shouldContain "SELECT tags[*] AS `tags[*]` FROM"
            }

            should("read back through the field that asked for it") {
                row("""{"tags[0]":"cs"}""")[Agenda.firstTag] shouldBe "cs"
                row("""{"tags[*]":["cs","lisp"]}""")[Agenda.everyTag] shouldBe listOf("cs", "lisp")
            }

            should("read back unprojected too, where the record carries the array itself") {
                row("""{"tags":["cs","lisp"]}""")[Agenda.firstTag] shouldBe "cs"
            }
        }

        context("a projection over a nested group") {
            should("name the object, which the server answers with whole") {
                val compiled = RecordingContext().select(Contacts).fields(Contacts.name, Contacts.address).compile()

                compiled.surql shouldContain "SELECT name, address FROM"
            }

            should("read every leaf under it through the declaration that named the leaf") {
                val contact = row("""{"address":{"city":"Boston","geo":{"lat":42.36}}}""")

                contact[Contacts.address.city] shouldBe "Boston"
                contact[Contacts.address.geo.lat] shouldBe 42.36
            }
        }

        context("a VALUE projection over an indexed path") {
            should("take no alias, because the answer is the value and has no key to carry one") {
                val compiled = RecordingContext().select(Agenda).value(Agenda.firstTag).compile()

                compiled.surql shouldContain "SELECT VALUE tags[0] FROM"
                compiled.surql shouldNotContain " AS "
            }
        }

        context("a RETURN clause naming fields") {
            should("alias an indexed path the same way, because RETURN projects what SELECT projects") {
                val compiled =
                    RecordingContext()
                        .update(Agenda)
                        .returnMode(ReturnMode.Fields(listOf(Agenda.firstTag)))
                        .compile()

                compiled.surql shouldContain " RETURN tags[0] AS `tags[0]`"
            }
        }
    })
