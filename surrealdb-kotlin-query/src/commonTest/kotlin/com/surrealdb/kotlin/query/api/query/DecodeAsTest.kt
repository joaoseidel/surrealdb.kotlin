package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.error.SurrealProtocolException
import com.surrealdb.kotlin.query.api.data.get
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

@Serializable
private data class Person(
    val id: RecordId,
    val name: String,
)

private fun answering(body: String): RecordingContext = RecordingContext(result = Json.parseToJsonElement(body))

class DecodeAsTest :
    ShouldSpec(
        {
            context("await") {
                should("decode every record of an array result") {
                    runTest {
                        val context =
                            answering(
                                """[{"id":"person:alice","name":"Ada"},{"id":"person:bob","name":"Bob"}]""",
                            )

                        val people = context.select(People).decodeAs<Person>().await()

                        people shouldBe
                            listOf(
                                Person(RecordId("person", "alice"), "Ada"),
                                Person(RecordId("person", "bob"), "Bob"),
                            )
                    }
                }

                should(
                    "decode a single-record result as a list of one, because ONLY changes the shape not the meaning",
                ) {
                    runTest {
                        val context = answering("""{"id":"person:alice","name":"Ada"}""")

                        context
                            .select(People)
                            .only()
                            .decodeAs<Person>()
                            .await() shouldBe
                            listOf(Person(RecordId("person", "alice"), "Ada"))
                    }
                }

                should("answer with an empty list when the statement matched nothing") {
                    runTest {
                        answering("null")
                            .select(People)
                            .decodeAs<Person>()
                            .await()
                            .shouldBeEmpty()
                    }
                }
            }

            context("awaitSingleOrNull") {
                should("decode the one record a record-id target answers with") {
                    runTest {
                        val context = answering("""{"id":"person:alice","name":"Ada"}""")

                        context.select(People["alice"]).decodeAs<Person>().awaitSingleOrNull() shouldBe
                            Person(RecordId("person", "alice"), "Ada")
                    }
                }

                should("answer null for a statement that matched nothing, which is what a missing record looks like") {
                    runTest {
                        answering("null").select(People["nobody"]).decodeAs<Person>().awaitSingleOrNull() shouldBe null
                    }
                }

                should("refuse a second record rather than picking one, because the caller asked the wrong question") {
                    runTest {
                        val context =
                            answering(
                                """[{"id":"person:alice","name":"Ada"},{"id":"person:bob","name":"Bob"}]""",
                            )

                        val failure =
                            shouldThrow<SurrealProtocolException> {
                                context.select(People).decodeAs<Person>().awaitSingleOrNull()
                            }

                        failure.message shouldContain "got 2"
                    }
                }
            }

            context("a result that is not made of records") {
                should("say so when read as rows, rather than handing back something that is not a record") {
                    runTest {
                        val failure =
                            shouldThrow<SurrealProtocolException> {
                                answering("""[[{"op":"replace","path":"/name","value":"Ada"}]]""")
                                    .patch(People, JsonArray(emptyList()))
                                    .returnMode(ReturnMode.Diff)
                                    .await()
                            }

                        failure.message shouldContain "Expected a record"
                    }
                }

                should("decode as the values themselves, which is what a VALUE projection answers with") {
                    runTest {
                        answering("""["Boston",null]""")
                            .select(People)
                            .value(People.name)
                            .decodeAs<String?>()
                            .await() shouldBe listOf("Boston", null)
                    }
                }

                should("decode the one value a scalar result answers with") {
                    runTest {
                        answering("42").select(People).decodeAs<Int>().awaitSingleOrNull() shouldBe 42
                    }
                }
            }

            context("the serializer") {
                should("come from the context, so a session's configuration reaches every builder") {
                    runTest {
                        val context =
                            RecordingContext(
                                json = Json { ignoreUnknownKeys = true },
                                result =
                                    Json.parseToJsonElement(
                                        """{"id":"person:alice","name":"Ada","unmodelled":1}""",
                                    ),
                            )

                        context.select(People).decodeAs<Person>().awaitSingleOrNull() shouldBe
                            Person(RecordId("person", "alice"), "Ada")
                    }
                }

                should("decode a record id without a contextual serializer being registered") {
                    runTest {
                        val context = answering("""{"id":"person:`with-dash`","name":"Ada"}""")

                        context
                            .select(People)
                            .decodeAs<Person>()
                            .awaitSingleOrNull()
                            ?.id shouldBe
                            RecordId("person", "with-dash")
                    }
                }
            }
        },
    )
