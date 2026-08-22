package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.api.ConnectionEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionEventsTest {
    private fun client(autoConnect: Boolean = false): Surreal {
        val engine =
            MockEngine { _ ->
                respond(
                    content = """{"id":"1","result":null}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        return Surreal(
            Surreal.Config(
                url = "http://localhost:8000",
                autoConnect = autoConnect,
                httpClientFactory = { _ -> HttpClient(engine) },
            ),
        )
    }

    @Test
    fun `http engine publishes Connected when start is called`() =
        runTest {
            val c = client(autoConnect = false)

            val received = mutableListOf<ConnectionEvent>()
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    c.connectionEvents.collect { received += it }
                }

            c.connect()
            advanceUntilIdle()

            assertTrue(received.first() is ConnectionEvent.Connected, "got ${received.first()}")
            job.cancel()
        }

    @Test
    fun `http engine start is idempotent and does not double-emit Connected`() =
        runTest {
            val c = client(autoConnect = false)

            val received = mutableListOf<ConnectionEvent>()
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    c.connectionEvents.collect { received += it }
                }

            c.connect()
            c.connect()
            advanceUntilIdle()

            val connectedCount = received.count { it is ConnectionEvent.Connected }
            assertEquals(1, connectedCount, "expected exactly 1 Connected event, got $connectedCount ($received)")
            job.cancel()
        }

    @Test
    fun `http engine publishes Disconnected on close`() =
        runBlocking {
            val c = client(autoConnect = false)
            val disconnected =
                async(start = CoroutineStart.UNDISPATCHED) {
                    c.connectionEvents.first { it is ConnectionEvent.Disconnected }
                }
            c.connect()
            c.close()
            assertTrue(withTimeout(2_000) { disconnected.await() } is ConnectionEvent.Disconnected)
        }
}
