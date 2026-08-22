package com.surrealdb.kotlin.api.live

import com.surrealdb.kotlin.api.error.SurrealLiveQueryException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private fun notification(
    liveQueryId: String,
    action: String = "CREATE",
    payload: String = "one",
) = LiveNotification(
    action = action,
    liveQueryId = liveQueryId,
    result = JsonPrimitive(payload),
    record = "book:$payload",
)

private val content: (kotlinx.serialization.json.JsonElement) -> String = { it.jsonPrimitive.content }

private class FakeEngine(
    private val startFails: Throwable? = null,
) {
    val notifications = MutableSharedFlow<LiveNotification>(extraBufferCapacity = 8)
    val failures = MutableSharedFlow<LiveQueryFailure>(extraBufferCapacity = 8)
    val started = mutableListOf<String>()
    val stopped = mutableListOf<String>()
    var onStarted: (String) -> Unit = {}

    fun <T> flow(decode: (kotlinx.serialization.json.JsonElement) -> T): Flow<LiveQueryEvent<T>> =
        liveEventFlow(
            notifications = notifications,
            failures = failures,
            start = {
                startFails?.let { throw it }
                val id = "lq-${started.size + 1}"
                started += id
                onStarted(id)
                id
            },
            stop = { id ->
                yield()
                stopped += id
            },
            decode = decode,
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> TestScope.collectInBackground(
    flow: Flow<T>,
    into: MutableList<T>,
): Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { into += it } }

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.collectOutcome(flow: Flow<*>): CompletableDeferred<Throwable?> {
    val outcome = CompletableDeferred<Throwable?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        outcome.complete(runCatching { flow.collect { } }.exceptionOrNull())
    }
    return outcome
}

class LiveQueryFlowTest :
    ShouldSpec({
        context("liveEventFlow") {

            context("the subscription's lifetime") {
                should("start nothing until something collects, so an unused flow costs the server no live query") {
                    runTest {
                        val engine = FakeEngine()

                        engine.flow(content)

                        engine.started.shouldBeEmpty()
                    }
                }

                should("start the query when collection begins") {
                    runTest {
                        val engine = FakeEngine()

                        collectInBackground(engine.flow(content), mutableListOf())

                        engine.started shouldContainExactly listOf("lq-1")
                    }
                }

                should("kill the query when the collector is cancelled, which is the ordinary way a collection ends") {
                    runTest {
                        val engine = FakeEngine()
                        val job = collectInBackground(engine.flow(content), mutableListOf())

                        job.cancelAndJoin()

                        engine.stopped shouldContainExactly listOf("lq-1")
                    }
                }

                should("kill the query when the collector finishes on its own terms") {
                    runTest {
                        val engine = FakeEngine()
                        engine.onStarted = { engine.notifications.tryEmit(notification(it)) }

                        engine.flow(content).take(1).toList()

                        engine.stopped shouldContainExactly listOf("lq-1")
                    }
                }

                should("kill nothing when the LIVE SELECT itself failed, there being no query to kill") {
                    runTest {
                        val engine = FakeEngine(startFails = IllegalStateException("LIVE SELECT rejected"))

                        shouldThrow<IllegalStateException> { engine.flow(content).toList() }

                        engine.stopped.shouldBeEmpty()
                    }
                }
            }

            context("a subscription that can no longer deliver") {
                should("throw the failure into the collector, so a dead query is not mistaken for a quiet one") {
                    runTest {
                        val engine = FakeEngine()
                        val outcome = collectOutcome(engine.flow(content))

                        engine.failures.emit(
                            LiveQueryFailure("lq-1", SurrealLiveQueryException("could not be re-established")),
                        )

                        outcome
                            .await()
                            .shouldBeInstanceOf<SurrealLiveQueryException>()
                            .message shouldBe "could not be re-established"
                    }
                }

                should("ignore a failure belonging to another query, which says nothing about this one") {
                    runTest {
                        val engine = FakeEngine()
                        val events = mutableListOf<LiveQueryEvent<String>>()
                        collectInBackground(engine.flow(content), events)

                        engine.failures.emit(
                            LiveQueryFailure("lq-2", SurrealLiveQueryException("someone else's query")),
                        )
                        engine.notifications.emit(notification("lq-1", payload = "mine"))

                        events.single().shouldBeInstanceOf<LiveQueryEvent.Created<String>>().value shouldBe "mine"
                    }
                }

                should("kill nothing, the query it would name having already stopped existing") {
                    runTest {
                        val engine = FakeEngine()
                        val outcome = collectOutcome(engine.flow(content))

                        engine.failures.emit(
                            LiveQueryFailure("lq-1", SurrealLiveQueryException("could not be re-established")),
                        )

                        outcome.await()
                        engine.stopped.shouldBeEmpty()
                    }
                }
            }

            context("each collection") {
                should(
                    "get a query of its own, so two collectors share no id and cancelling one cannot end the other",
                ) {
                    runTest {
                        val engine = FakeEngine()
                        val flow = engine.flow(content)

                        val first = collectInBackground(flow, mutableListOf())
                        collectInBackground(flow, mutableListOf())

                        engine.started shouldContainExactly listOf("lq-1", "lq-2")

                        first.cancelAndJoin()
                        engine.stopped shouldContainExactly listOf("lq-1")
                    }
                }
            }

            context("the window between LIVE SELECT and collection") {
                should(
                    "deliver a notification published before the statement's reply came back, because the collector is subscribed before the statement runs",
                ) {
                    runTest {
                        val engine = FakeEngine()
                        val events = mutableListOf<LiveQueryEvent<String>>()

                        engine.onStarted = { engine.notifications.tryEmit(notification(it, payload = "early")) }

                        collectInBackground(engine.flow(content), events)

                        events.single().shouldBeInstanceOf<LiveQueryEvent.Created<String>>().value shouldBe "early"
                    }
                }
            }

            context("the events") {
                should("carry only the notifications belonging to this collection's query") {
                    runTest {
                        val engine = FakeEngine()
                        val events = mutableListOf<LiveQueryEvent<String>>()
                        collectInBackground(engine.flow(content), events)

                        engine.notifications.emit(notification("lq-2", payload = "someone else's"))
                        engine.notifications.emit(notification("lq-1", payload = "mine"))

                        events.single().shouldBeInstanceOf<LiveQueryEvent.Created<String>>().value shouldBe "mine"
                    }
                }

                should("decode each payload with the decoder they were asked for") {
                    runTest {
                        val engine = FakeEngine()
                        val events = mutableListOf<LiveQueryEvent<Int>>()
                        collectInBackground(engine.flow { it.jsonPrimitive.content.length }, events)

                        engine.notifications.emit(notification("lq-1", payload = "four"))

                        events.single().shouldBeInstanceOf<LiveQueryEvent.Created<Int>>().value shouldBe 4
                    }
                }

                should("keep the action's meaning, so a collector branches on a type rather than a string") {
                    runTest {
                        val engine = FakeEngine()
                        val events = mutableListOf<LiveQueryEvent<String>>()
                        collectInBackground(engine.flow(content), events)

                        engine.notifications.emit(notification("lq-1", action = "DELETE"))

                        events.single().shouldBeInstanceOf<LiveQueryEvent.Deleted<String>>()
                    }
                }
            }
        }
    })
