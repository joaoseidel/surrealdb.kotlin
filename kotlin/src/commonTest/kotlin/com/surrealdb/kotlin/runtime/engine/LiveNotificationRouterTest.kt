package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.error.SurrealTransportException
import com.surrealdb.kotlin.api.live.SurrealLiveNotification
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
private fun TestScope.broadcastInto(router: LiveNotificationRouter): MutableList<SurrealLiveNotification> {
    val seen = mutableListOf<SurrealLiveNotification>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        router.notifications.collect { seen += it }
    }
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
                        val seen = broadcastInto(router)

                        router.route(notification(liveQueryId = "not-registered-yet"))

                        seen shouldContainExactly listOf(notification(liveQueryId = "not-registered-yet"))
                    }
                }

                should("carry a notification that also reached a subscription, so neither consumer takes it from the other") {
                    runTest {
                        val router = LiveNotificationRouter()
                        val seen = broadcastInto(router)
                        val events = router.register("lq-1")

                        router.route(notification())

                        events.first() shouldBe notification()
                        seen shouldContainExactly listOf(notification())
                    }
                }

                should("keep carrying notifications after the subscription they belong to is gone") {
                    runTest {
                        val router = LiveNotificationRouter()
                        val seen = broadcastInto(router)
                        router.register("lq-1")
                        router.unregister("lq-1")

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
                        router.unregister("lq-1")

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

                        router.unregister("lq-1")

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

            context("closeAll") {
                should("fail every subscription with the cause, so a collector learns the connection died instead of going quiet") {
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
