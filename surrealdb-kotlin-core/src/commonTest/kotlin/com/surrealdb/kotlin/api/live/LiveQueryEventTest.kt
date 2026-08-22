package com.surrealdb.kotlin.api.live

import com.surrealdb.kotlin.api.data.RecordId
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun notification(
    action: String,
    record: String? = "book:lq1",
) = LiveNotification(
    action = action,
    liveQueryId = "1184a6bc-6968-462c-b2d6-8f6ac1ac5fd0",
    result = Json.parseToJsonElement("""{"id":"book:lq1","pages":1,"title":"Live One"}"""),
    record = record,
)

private val title: (JsonElement) -> String = {
    it.jsonObject
        .getValue("title")
        .jsonPrimitive.content
}

class LiveQueryEventTest :
    ShouldSpec({
        context("LiveNotification.toEvent") {
            context("the action") {
                should("map CREATE to Created, so a caller branches on a type rather than a string") {
                    notification("CREATE")
                        .toEvent(title)
                        .shouldBeInstanceOf<LiveQueryEvent.Created<String>>()
                }

                should("map UPDATE to Updated") {
                    notification("UPDATE")
                        .toEvent(title)
                        .shouldBeInstanceOf<LiveQueryEvent.Updated<String>>()
                }

                should("map DELETE to Deleted") {
                    notification("DELETE")
                        .toEvent(title)
                        .shouldBeInstanceOf<LiveQueryEvent.Deleted<String>>()
                }

                should("match the action whatever its case, since the wire casing is not guaranteed") {
                    notification("create")
                        .toEvent(title)
                        .shouldBeInstanceOf<LiveQueryEvent.Created<String>>()
                }

                should("surface an unmodelled action as Other, so a newer server cannot break a collector") {
                    notification("KILLED")
                        .toEvent(title)
                        .shouldBeInstanceOf<LiveQueryEvent.Other<String>>()
                        .action shouldBe "KILLED"
                }
            }

            context("the payload") {
                should("decode the last known record on DELETE") {
                    val event = notification("DELETE").toEvent(title)

                    event
                        .shouldBeInstanceOf<LiveQueryEvent.Deleted<String>>()
                        .value shouldBe "Live One"
                }

                should("carry the live query id through, because kill takes it") {
                    notification("CREATE")
                        .toEvent(title)
                        .queryId shouldBe "1184a6bc-6968-462c-b2d6-8f6ac1ac5fd0"
                }
            }

            context("the record field") {
                should("read table:id as a RecordId, so the event says what it concerns") {
                    notification("CREATE")
                        .toEvent(title)
                        .record shouldBe RecordId("book", "lq1")
                }

                should("split on the first colon, so an id that contains one survives intact") {
                    notification("CREATE", record = "book:a:b")
                        .toEvent(title)
                        .record shouldBe RecordId("book", "a:b")
                }

                should("answer null for anything that is not table:id, rather than throw") {
                    notification("CREATE", record = null)
                        .toEvent(title)
                        .record
                        .shouldBeNull()

                    notification("CREATE", record = "book")
                        .toEvent(title)
                        .record
                        .shouldBeNull()

                    notification("CREATE", record = ":lq1")
                        .toEvent(title)
                        .record
                        .shouldBeNull()

                    notification("CREATE", record = "book:")
                        .toEvent(title)
                        .record
                        .shouldBeNull()
                }
            }
        }
    })
