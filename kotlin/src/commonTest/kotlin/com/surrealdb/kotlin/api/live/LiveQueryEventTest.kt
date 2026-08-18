package com.surrealdb.kotlin.api.live

import com.surrealdb.kotlin.api.query.RecordId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LiveQueryEventTest {
    private fun notification(action: String, record: String? = "book:lq1") =
        SurrealLiveNotification(
            action = action,
            liveQueryId = "1184a6bc-6968-462c-b2d6-8f6ac1ac5fd0",
            result = Json.parseToJsonElement("""{"id":"book:lq1","pages":1,"title":"Live One"}"""),
            record = record,
        )

    private val title: (JsonElement) -> String = { it.jsonObject.getValue("title").jsonPrimitive.content }

    @Test
    fun `CREATE UPDATE and DELETE map to their own types`() {
        assertIs<LiveQueryEvent.Created<String>>(notification("CREATE").toEvent(title))
        assertIs<LiveQueryEvent.Updated<String>>(notification("UPDATE").toEvent(title))
        assertIs<LiveQueryEvent.Deleted<String>>(notification("DELETE").toEvent(title))
    }

    @Test
    fun `DELETE carries the last known record, not just its id`() {
        // SurrealDB sends the whole record on delete; the documentation says otherwise, so this
        // pins the observed behaviour rather than the documented one.
        val event = assertIs<LiveQueryEvent.Deleted<String>>(notification("DELETE").toEvent(title))
        assertEquals("Live One", event.value)
    }

    @Test
    fun `an unmodelled action is surfaced rather than dropped`() {
        // SurrealDB emits KILLED when a live query ends, and may add further actions.
        val event = assertIs<LiveQueryEvent.Other<String>>(notification("KILLED").toEvent(title))
        assertEquals("KILLED", event.action)
    }

    @Test
    fun `actions are matched case-insensitively`() {
        assertIs<LiveQueryEvent.Created<String>>(notification("create").toEvent(title))
    }

    @Test
    fun `the query id is carried through, because kill needs it`() {
        val event = notification("CREATE").toEvent(title)
        assertEquals("1184a6bc-6968-462c-b2d6-8f6ac1ac5fd0", event.queryId)
    }

    @Test
    fun `record is parsed into a RecordId`() {
        assertEquals(RecordId("book", "lq1"), notification("CREATE").toEvent(title).record)
    }

    @Test
    fun `an id half containing a colon survives the split`() {
        val event = notification("CREATE", record = "book:a:b").toEvent(title)
        assertEquals(RecordId("book", "a:b"), event.record)
    }

    @Test
    fun `a missing or unparseable record yields null rather than throwing`() {
        assertNull(notification("CREATE", record = null).toEvent(title).record)
        assertNull(notification("CREATE", record = "book").toEvent(title).record)
        assertNull(notification("CREATE", record = ":lq1").toEvent(title).record)
        assertNull(notification("CREATE", record = "book:").toEvent(title).record)
    }
}
