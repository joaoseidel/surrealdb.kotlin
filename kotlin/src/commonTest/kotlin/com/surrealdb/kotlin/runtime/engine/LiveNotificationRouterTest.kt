package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.error.SurrealTransportException
import com.surrealdb.kotlin.api.live.SurrealLiveNotification
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

private fun notification(
    liveQueryId: String = "lq-1",
    action: String = "CREATE",
) = SurrealLiveNotification(
    action = action,
    liveQueryId = liveQueryId,
    result = JsonPrimitive("$liveQueryId/$action"),
)

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> TestScope.collectInto(flow: Flow<T>): MutableList<T> {
    val seen = mutableListOf<T>()
    // Unconfined, deliberately: a plain launch has not reached its collect by the
    // time the test routes a notification, which would make every assertion below a
    // race. An unconfined one runs to the first suspension — the subscription —
    // before this returns.
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
    return seen
}

class LiveNotificationRouterTest :
    ShouldSpec({
        context("LiveNotificationRouter") {
            context("the broadcast") {
                should(
                    "carry a notification no subscription is registered for, so the window between running a LIVE SELECT and learning its id cannot lose one",
                ) {
                    runTest {
                        val router = LiveNotificationRouter()
                        val seen = collectInto(router.notifications)

                        router.route(notification(liveQueryId = "not-registered-yet"))

                        seen shouldContainExactly listOf(notification(liveQueryId = "not-registered-yet"))
                    }
                }

                should(
                    "carry a notification that also reached a subscription, so neither consumer takes it from the other",
                ) {
                    runTest {
                        val router = LiveNotificationRouter()
                        val seen = collectInto(router.notifications)
                        val events = router.register("lq-1")

                        router.route(notification())

                        events.first() shouldBe notification()
                        seen shouldContainExactly listOf(notification())
                    }
                }

                should("keep carrying notifications after the subscription they belong to is gone") {
                    runTest {
                        val router = LiveNotificationRouter()
                        val seen = collectInto(router.notifications)
                        router.register("lq-1")
                        router.untrack("lq-1")

                        router.route(notification())

                        seen shouldContainExactly listOf(notification())
                    }
                }
            }

            context("a registered subscription") {
                should("receive the notifications carrying its own id") {
                    runTest {
                        val router = LiveNotificationRouter()
                        val events = router.register("lq-1")

                        router.route(notification(action = "CREATE"))

                        events.first() shouldBe notification(action = "CREATE")
                    }
                }

                should("skip a notification belonging to another query, so two live queries do not cross") {
                    runTest {
                        val router = LiveNotificationRouter()
                        val events = router.register("lq-1")

                        router.route(notification(liveQueryId = "lq-2"))
                        router.route(notification(liveQueryId = "lq-1"))

                        events.first() shouldBe notification(liveQueryId = "lq-1")
                    }
                }

                should(
                    "hold notifications that arrive before anyone collects, since the collector is registered separately from the socket",
                ) {
                    runTest {
                        val router = LiveNotificationRouter()
                        val events = router.register("lq-1")

                        router.route(notification(action = "CREATE"))
                        router.route(notification(action = "UPDATE"))
                        router.untrack("lq-1")

                        events.toList() shouldContainExactly
                            listOf(
                                notification(action = "CREATE"),
                                notification(action = "UPDATE"),
                            )
                    }
                }

                should("end when it is unregistered, rather than leave its collector suspended forever") {
                    runTest {
                        val router = LiveNotificationRouter()
                        val events = router.register("lq-1")

                        router.untrack("lq-1")

                        events.toList().shouldBeEmpty()
                    }
                }

                should("end when the same id is registered again, because notifications now go to the replacement") {
                    runTest {
                        val router = LiveNotificationRouter()
                        val replaced = router.register("lq-1")
                        val current = router.register("lq-1")

                        router.route(notification())

                        replaced.toList().shouldBeEmpty()
                        current.first() shouldBe notification()
                    }
                }
            }

            context("activeQueries") {
                should("start empty, so nothing is reported running before anything is") {
                    runTest {
                        LiveNotificationRouter().activeQueries.value.shouldBeEmpty()
                    }
                }

                should(
                    "list a query as soon as it is registered, so a caller can wait for the subscription to exist rather than sleep",
                ) {
                    runTest {
                        val router = LiveNotificationRouter()

                        router.register("lq-1")

                        router.activeQueries.value shouldBe setOf("lq-1")
                    }
                }

                should("list a query tracked without a channel, since a collector filtering the broadcast opens none") {
                    runTest {
                        val router = LiveNotificationRouter()

                        router.track("lq-1")

                        router.activeQueries.value shouldBe setOf("lq-1")
                    }
                }

                should("drop only the query that was untracked, leaving the connection's others running") {
                    runTest {
                        val router = LiveNotificationRouter()
                        router.track("lq-1")
                        router.track("lq-2")

                        router.untrack("lq-1")

                        router.activeQueries.value shouldBe setOf("lq-2")
                    }
                }

                should("ignore an untrack for an id it never had, so killing a subscription twice is harmless") {
                    runTest {
                        val router = LiveNotificationRouter()
                        router.track("lq-1")

                        router.untrack("lq-1")
                        router.untrack("lq-1")

                        router.activeQueries.value.shouldBeEmpty()
                    }
                }

                should("empty when the connection carrying the queries is gone") {
                    runTest {
                        val router = LiveNotificationRouter()
                        router.register("lq-1")
                        router.track("lq-2")

                        router.closeAll(SurrealTransportException("WebSocket terminated"))

                        router.activeQueries.value.shouldBeEmpty()
                    }
                }

                should("report each change to a collector, so waiting on a subscription needs no polling") {
                    runTest {
                        val router = LiveNotificationRouter()
                        val seen = collectInto(router.activeQueries)

                        router.track("lq-1")
                        router.untrack("lq-1")

                        seen shouldContainExactly listOf(emptySet(), setOf("lq-1"), emptySet())
                    }
                }
            }

            context("closeAll") {
                should(
                    "fail every subscription with the cause, so a collector learns the connection died instead of going quiet",
                ) {
                    runTest {
                        val router = LiveNotificationRouter()
                        val events = router.register("lq-1")

                        router.closeAll(SurrealTransportException("WebSocket terminated"))

                        shouldThrow<SurrealTransportException> { events.toList() }
                    }
                }

                should("end a subscription without a cause when the client closed deliberately") {
                    runTest {
                        val router = LiveNotificationRouter()
                        val events = router.register("lq-1")

                        router.closeAll()

                        events.toList().shouldBeEmpty()
                    }
                }
            }
        }
    })
