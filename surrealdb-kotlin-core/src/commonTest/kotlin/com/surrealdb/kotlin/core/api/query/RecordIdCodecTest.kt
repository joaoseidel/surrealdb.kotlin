package com.surrealdb.kotlin.core.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.RecordKey
import com.surrealdb.kotlin.core.api.data.parseRecordId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.uuid.Uuid

private const val UUID_TEXT = "0196a3c2-1234-7abc-8def-0123456789ab"
private val uuid = Uuid.parse(UUID_TEXT)

/**
 * The JSON form of a record id, in both directions, for every kind of key.
 *
 * Every string here was taken from SurrealDB v3.2.4: it answers with
 * `person:alice` for an `id` field, backticks a string key that is not a bare
 * identifier or that reads as a number, writes an integer key bare, a uuid
 * key as `person:u'...'`, and array and object keys as SurrealQL literals.
 * It reads the same strings back into a `record` field, and reads an
 * unquoted `person:a-b` as `person:a` minus `b`, storing `person:a` with
 * status OK.
 */
class RecordIdCodecTest :
    ShouldSpec(
        {
            context("a text key") {
                should("read a bare key") {
                    parseRecordId("person:alice") shouldBe RecordId("person", "alice")
                }

                should("unquote a key the server had to quote, so the key is the text and not its spelling") {
                    parseRecordId("person:`with-dash`") shouldBe RecordId("person", "with-dash")
                    parseRecordId("person:`needs space`") shouldBe RecordId("person", "needs space")
                }

                should("read a quoted digit run as text, because that is what the quotes mean") {
                    parseRecordId("person:`1`") shouldBe RecordId("person", "1")
                    parseRecordId("person:`99999999999999999999`") shouldBe RecordId("person", "99999999999999999999")
                }

                should("read a bare key that only looks typed as text, which is how the server writes it") {
                    val keys = listOf("1e5", "true", "123abc", "01M2S166CAAZTVYSVWMHV2YFM4", "80cq8g0cbyl9z4t7vpqh")

                    keys.forAll { key -> parseRecordId("person:$key") shouldBe RecordId("person", key) }
                }

                should("unescape a backtick, a backslash and a newline inside a quoted key") {
                    parseRecordId("person:`has\\`tick`") shouldBe RecordId("person", "has`tick")
                    parseRecordId("person:`back\\\\slash`") shouldBe RecordId("person", "back\\slash")
                    parseRecordId("person:`a\\nb`") shouldBe RecordId("person", "a\nb")
                }

                should("read a key with a colon inside its quotes, rather than stop at the colon") {
                    parseRecordId("person:`a:b`") shouldBe RecordId("person", "a:b")
                }

                should("read the angle-bracket quoting SurrealDB accepts, though it writes backticks") {
                    parseRecordId("person:⟨with-dash⟩") shouldBe RecordId("person", "with-dash")
                    parseRecordId("⟨my-table⟩:alice") shouldBe RecordId("my-table", "alice")
                }

                should("unquote a quoted table, which the server writes for a table name it had to quote") {
                    parseRecordId("`my-table`:alice") shouldBe RecordId("my-table", "alice")
                }

                should("quote a key that is not a bare identifier, because the server reads person:a-b as person:a") {
                    RecordId("person", "with-dash").toString() shouldBe "person:`with-dash`"
                    RecordId("person", "needs space").toString() shouldBe "person:`needs space`"
                    RecordId("person", "a:b").toString() shouldBe "person:`a:b`"
                }

                should(
                    "quote a key that would read back as a number, so it names the string key and not the integer one",
                ) {
                    RecordId("person", "1").toString() shouldBe "person:`1`"
                    RecordId("person", "1e5").toString() shouldBe "person:`1e5`"
                    RecordId("person", "42dec").toString() shouldBe "person:`42dec`"
                }

                should("escape a backtick and a backslash inside a quoted key") {
                    RecordId("person", "has`tick").toString() shouldBe "person:`has\\`tick`"
                    RecordId("person", "back\\slash").toString() shouldBe "person:`back\\\\slash`"
                }

                should("quote a table name that is not a bare identifier") {
                    RecordId("my-table", "alice").toString() shouldBe "`my-table`:alice"
                }

                should("leave a bare pair unquoted, a generated key beginning with a digit included") {
                    RecordId("person", "alice").toString() shouldBe "person:alice"
                    RecordId("person", "80cq8g0cbyl9z4t7vpqh").toString() shouldBe "person:80cq8g0cbyl9z4t7vpqh"
                }

                should("quote uuid text, so it names the string key and not the uuid one") {
                    RecordId("person", UUID_TEXT).toString() shouldBe "person:`$UUID_TEXT`"
                }

                should("differ from the integer key with the same digits, because the server keeps two records") {
                    RecordId("person", "1") shouldNotBe RecordId("person", 1)
                }
            }

            context("an integer key") {
                should("read a bare digit run as the integer, which is how the server writes user:1") {
                    parseRecordId("person:1") shouldBe RecordId("person", 1)
                    parseRecordId("person:-5") shouldBe RecordId("person", -5)
                }

                should("cover the whole i64 range SurrealDB stores") {
                    parseRecordId("person:9223372036854775807") shouldBe RecordId("person", Long.MAX_VALUE)
                    parseRecordId("person:-9223372036854775808") shouldBe RecordId("person", Long.MIN_VALUE)
                }

                should("write the digits bare, so the server reads the integer back") {
                    RecordId("person", 1).toString() shouldBe "person:1"
                    RecordId("person", -5).toString() shouldBe "person:-5"
                }
            }

            context("a uuid key") {
                should("read the u'...' spelling to the uuid, so the kind survives the trip") {
                    parseRecordId("person:u'$UUID_TEXT'") shouldBe RecordId("person", uuid)
                    parseRecordId("person:u\"$UUID_TEXT\"") shouldBe RecordId("person", uuid)
                }

                should("write the u'...' spelling in lower case, as the server does") {
                    RecordId("person", uuid).toString() shouldBe "person:u'$UUID_TEXT'"
                }

                should("differ from the string key holding the same text") {
                    RecordId("person", uuid) shouldNotBe RecordId("person", UUID_TEXT)
                }

                should("reject text between u'...' that is not a uuid") {
                    shouldThrow<SerializationException> { parseRecordId("person:u'not-a-uuid'") }
                }
            }

            context("an array key") {
                val ab1 =
                    RecordId(
                        "person",
                        RecordKey.Array(
                            buildJsonArray {
                                add("a")
                                add(1)
                            },
                        ),
                    )

                should("read the literal the server writes, with elements as JSON") {
                    parseRecordId("person:['a', 1]") shouldBe ab1
                    parseRecordId("person:[\"a\", 1]") shouldBe ab1
                    parseRecordId("person:[]") shouldBe RecordId("person", RecordKey.Array(buildJsonArray { }))
                }

                should("read every element spelling a JSON-bound key comes back with") {
                    parseRecordId("person:[\"it's\", 'a\"b', 'c`d', 'e\\\\f', 'x\\'y', 'a\\nb']") shouldBe
                        RecordId(
                            "person",
                            RecordKey.Array(
                                buildJsonArray {
                                    add("it's")
                                    add("a\"b")
                                    add("c`d")
                                    add("e\\f")
                                    add("x'y")
                                    add("a\nb")
                                },
                            ),
                        )
                    parseRecordId("person:[1.5f, true, NULL, NONE, -3, 9007199254740993]") shouldBe
                        RecordId(
                            "person",
                            RecordKey.Array(
                                buildJsonArray {
                                    add(1.5)
                                    add(true)
                                    add(JsonNull)
                                    add(JsonNull)
                                    add(-3)
                                    add(9007199254740993L)
                                },
                            ),
                        )
                }

                should("read a uuid element and a record element back to their text, since JSON carries neither kind") {
                    parseRecordId("person:[u'$UUID_TEXT', 2]") shouldBe
                        RecordId(
                            "person",
                            RecordKey.Array(
                                buildJsonArray {
                                    add(UUID_TEXT)
                                    add(2)
                                },
                            ),
                        )
                    parseRecordId("person:[a:b, 'c']") shouldBe
                        RecordId(
                            "person",
                            RecordKey.Array(
                                buildJsonArray {
                                    add("a:b")
                                    add("c")
                                },
                            ),
                        )
                }

                should("read nested arrays and objects") {
                    parseRecordId("person:[{ a: 1 }, [1, 2]]") shouldBe
                        RecordId(
                            "person",
                            RecordKey.Array(
                                buildJsonArray {
                                    add(buildJsonObject { put("a", JsonPrimitive(1)) })
                                    add(
                                        buildJsonArray {
                                            add(1)
                                            add(2)
                                        },
                                    )
                                },
                            ),
                        )
                }

                should("write the literal the server reads, quoting strings and marking floats") {
                    ab1.toString() shouldBe "person:['a', 1]"
                    RecordId(
                        "person",
                        RecordKey.Array(
                            buildJsonArray {
                                add("it's")
                                add(1.5)
                                add(true)
                                add(JsonNull)
                                add("e\\f")
                            },
                        ),
                    ).toString() shouldBe "person:['it\\'s', 1.5f, true, NULL, 'e\\\\f']"
                }

                should(
                    "write uuid text and table:key text the way the server coerces them, so a link and a target agree",
                ) {
                    RecordId(
                        "person",
                        RecordKey.Array(
                            buildJsonArray {
                                add("x")
                                add(UUID_TEXT)
                                add(5)
                            },
                        ),
                    ).toString() shouldBe
                        "person:['x', u'$UUID_TEXT', 5]"
                    RecordId(
                        "person",
                        RecordKey.Array(
                            buildJsonArray {
                                add("a:b")
                                add("c")
                            },
                        ),
                    ).toString() shouldBe
                        "person:[a:b, 'c']"
                }

                should("reject an unterminated array") {
                    shouldThrow<SerializationException> { parseRecordId("person:['a'") }
                    shouldThrow<SerializationException> { parseRecordId("person:['a', ]") }
                }
            }

            context("an object key") {
                val a1bx =
                    RecordId(
                        "person",
                        RecordKey.Object(
                            buildJsonObject {
                                put("a", JsonPrimitive(1))
                                put("b", JsonPrimitive("x"))
                            },
                        ),
                    )

                should("read the literal the server writes, with fields as JSON") {
                    parseRecordId("person:{ a: 1, b: 'x' }") shouldBe a1bx
                    parseRecordId("person:{ \"a\": 1, 'b': \"x\" }") shouldBe a1bx
                    parseRecordId("person:{  }") shouldBe RecordId("person", RecordKey.Object(buildJsonObject { }))
                    parseRecordId("person:{}") shouldBe RecordId("person", RecordKey.Object(buildJsonObject { }))
                }

                should("read a quoted field name, which the server writes for a name that is not an identifier") {
                    parseRecordId("person:{ \"back-tick\": 2, \"has space\": 1, \"q'uote\": 3 }") shouldBe
                        RecordId(
                            "person",
                            RecordKey.Object(
                                buildJsonObject {
                                    put("back-tick", JsonPrimitive(2))
                                    put("has space", JsonPrimitive(1))
                                    put("q'uote", JsonPrimitive(3))
                                },
                            ),
                        )
                }

                should("equal the same fields in another order, because the server sorts them") {
                    parseRecordId("person:{ b: 'x', a: 1 }") shouldBe a1bx
                }

                should("write the fields sorted, as the server does, quoting a name that is not an identifier") {
                    RecordId(
                        "person",
                        RecordKey.Object(
                            buildJsonObject {
                                put("b", JsonPrimitive("x"))
                                put("a", JsonPrimitive(1))
                            },
                        ),
                    ).toString() shouldBe "person:{ a: 1, b: 'x' }"
                    RecordId(
                        "person",
                        RecordKey.Object(
                            buildJsonObject {
                                put("has space", JsonPrimitive(1))
                                put("d\"q", JsonPrimitive(2))
                            },
                        ),
                    ).toString() shouldBe "person:{ \"d\\\"q\": 2, \"has space\": 1 }"
                    RecordId("person", RecordKey.Object(buildJsonObject { })).toString() shouldBe "person:{  }"
                }

                should("reject an unterminated object and a field without a value") {
                    shouldThrow<SerializationException> { parseRecordId("person:{ a: 1") }
                    shouldThrow<SerializationException> { parseRecordId("person:{ a }") }
                }
            }

            context("a string that is not a record id") {
                should("reject one that names no key, rather than inventing an empty one") {
                    shouldThrow<SerializationException> { parseRecordId("person") }
                }

                should("reject an unterminated quote") {
                    shouldThrow<SerializationException> { parseRecordId("person:`alice") }
                    shouldThrow<SerializationException> { parseRecordId("person:⟨alice") }
                }

                should("reject one missing either half, since half an id names no record") {
                    shouldThrow<SerializationException> { parseRecordId(":alice") }
                    shouldThrow<SerializationException> { parseRecordId("person:") }
                }

                should("reject trailing text after a quoted or bracketed key") {
                    shouldThrow<SerializationException> { parseRecordId("person:`alice`x") }
                    shouldThrow<SerializationException> { parseRecordId("person:[1]x") }
                }

                should("reject a colon in a bare key, which the server never writes and would not parse") {
                    shouldThrow<SerializationException> { parseRecordId("person:a:b") }
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
                    listOf(
                        "with-dash",
                        "needs space",
                        "has`tick",
                        "123",
                        "back\\slash",
                        "a:b",
                        "a\nb",
                        UUID_TEXT,
                    ).forAll { key ->
                        parseRecordId(RecordId("person", key).toString()) shouldBe RecordId("person", key)
                    }
                }

                should("round-trip every kind of key") {
                    listOf(
                        RecordId("person", 1),
                        RecordId("person", -5),
                        RecordId("person", uuid),
                        RecordId(
                            "person",
                            RecordKey.Array(
                                buildJsonArray {
                                    add("a")
                                    add(1)
                                    add(buildJsonArray { add(1.5) })
                                },
                            ),
                        ),
                        RecordId(
                            "person",
                            RecordKey.Object(
                                buildJsonObject {
                                    put("a", JsonPrimitive(1))
                                    put("has space", JsonNull)
                                },
                            ),
                        ),
                    ).forAll { id -> parseRecordId(id.toString()) shouldBe id }
                }
            }
        },
    )
