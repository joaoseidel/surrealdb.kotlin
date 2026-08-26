package com.surrealdb.kotlin.core.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.parseRecordId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON form of a record id, in both directions.
 *
 * SurrealDB v3.2.4 answers with `person:alice` for an `id` field, quotes a key
 * that is not a bare identifier in backticks, and shows a uuid key as
 * `person:u'...'`. It reads the same string back into a `record` field, and
 * reads an unquoted `person:a-b` as `person:a` minus `b`, storing `person:a`
 * with status OK. Every string here was taken from that server.
 */
class RecordIdCodecTest :
    ShouldSpec(
        {
            context("decoding the string a record id arrives as") {
                should("read a bare key") {
                    parseRecordId("person:alice") shouldBe RecordId("person", "alice")
                }

                should("unquote a key the server had to quote, so the key is the text and not its spelling") {
                    parseRecordId("person:`with-dash`") shouldBe RecordId("person", "with-dash")
                    parseRecordId("person:`needs space`") shouldBe RecordId("person", "needs space")
                }

                should("unescape a backtick inside a quoted key") {
                    parseRecordId("person:`has\\`tick`") shouldBe RecordId("person", "has`tick")
                }

                should("unquote a quoted table, which the server writes for a table name it had to quote") {
                    parseRecordId("`my-table`:alice") shouldBe RecordId("my-table", "alice")
                }

                should("read a uuid key as its text, because that text names the same record again") {
                    parseRecordId("person:u'019624ff-0000-7000-8000-000000000000'") shouldBe
                        RecordId("person", "019624ff-0000-7000-8000-000000000000")
                }

                should("reject a string that names no key, rather than inventing an empty one") {
                    shouldThrow<SerializationException> { parseRecordId("person") }
                }

                should("reject an unterminated quote") {
                    shouldThrow<SerializationException> { parseRecordId("person:`alice") }
                }

                should("reject a string missing either half, since half an id names no record") {
                    shouldThrow<SerializationException> { parseRecordId(":alice") }
                    shouldThrow<SerializationException> { parseRecordId("person:") }
                }
            }

            context("the string a record id is written as") {
                should("quote a key that is not a bare identifier, because the server reads person:a-b as person:a") {
                    RecordId("person", "with-dash").toString() shouldBe "person:`with-dash`"
                    RecordId("person", "needs space").toString() shouldBe "person:`needs space`"
                }

                should("quote a numeric key, so it names the string key and not the integer one") {
                    RecordId("person", "123").toString() shouldBe "person:`123`"
                }

                should("escape a backtick and a backslash inside a quoted key") {
                    RecordId("person", "has`tick").toString() shouldBe "person:`has\\`tick`"
                    RecordId("person", "back\\slash").toString() shouldBe "person:`back\\\\slash`"
                }

                should("quote a table name that is not a bare identifier") {
                    RecordId("my-table", "alice").toString() shouldBe "`my-table`:alice"
                }

                should("leave a bare pair unquoted") {
                    RecordId("person", "alice").toString() shouldBe "person:alice"
                }

                should(
                    "leave a generated key beginning with a digit unquoted, which is how the server writes it back",
                ) {
                    RecordId("person", "80cq8g0cbyl9z4t7vpqh").toString() shouldBe "person:80cq8g0cbyl9z4t7vpqh"
                }

                should("quote a key that would read back as a number, which names a different record") {
                    RecordId("person", "1").toString() shouldBe "person:`1`"
                    RecordId("person", "1e5").toString() shouldBe "person:`1e5`"
                    RecordId("person", "42dec").toString() shouldBe "person:`42dec`"
                }
            }

            context("through kotlinx.serialization") {
                should("decode the id field of a record without a contextual serializer being registered") {
                    Json.decodeFromJsonElement(
                        RecordId.serializer(),
                        JsonPrimitive("person:alice"),
                    ) shouldBe RecordId("person", "alice")
                }

                should("encode as the string form, so a decoded id can be sent straight back") {
                    Json.encodeToJsonElement(
                        RecordId.serializer(),
                        RecordId("person", "with-dash"),
                    ) shouldBe JsonPrimitive("person:`with-dash`")
                }

                should("round-trip every key the server has to quote") {
                    listOf("with-dash", "needs space", "has`tick", "123", "back\\slash").forAll { key ->
                        parseRecordId(RecordId("person", key).toString()) shouldBe RecordId("person", key)
                    }
                }
            }
        },
    )
