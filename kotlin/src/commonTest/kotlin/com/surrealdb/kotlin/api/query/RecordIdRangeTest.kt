package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordIdRange
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * A record range is the one target the builder inlines part of — SurrealQL has
 * no `type::range` constructor, so the table name reaches the query string
 * directly and the bounds are the only halves that can be bound.
 */
class RecordIdRangeTest :
    ShouldSpec({
        context("a RecordIdRange rendered into a query") {
            should("bind both bounds, so neither can carry SurrealQL into the string") {
                val q = BoundQuery()

                q.appendValue(RecordIdRange("person", start = "alice", end = "zara"))

                q.surql shouldBe "person:\$_0..\$_1"
                q.bindings["_0"] shouldBe JsonPrimitive("alice")
                q.bindings["_1"] shouldBe JsonPrimitive("zara")
            }

            should("close the range inclusively when includeEnd is set") {
                val q = BoundQuery()

                q.appendValue(RecordIdRange("person", start = "alice", end = "zara", includeEnd = true))

                q.surql shouldBe "person:\$_0..=\$_1"
            }

            should("close inclusively with no start too, because the two bounds are independent") {
                val q = BoundQuery()

                q.appendValue(RecordIdRange("person", end = "zara", includeEnd = true))

                q.surql shouldBe "person:..=\$_0"
            }

            should("leave the open side empty when only one bound is given") {
                val fromAlice = BoundQuery()
                val untilZara = BoundQuery()

                fromAlice.appendValue(RecordIdRange("person", start = "alice"))
                untilZara.appendValue(RecordIdRange("person", end = "zara"))

                fromAlice.surql shouldBe "person:\$_0.."
                untilZara.surql shouldBe "person:..\$_0"
            }

            should("reject a table name that is not a plain identifier, because it is inlined") {
                val q = BoundQuery()

                shouldThrow<IllegalArgumentException> {
                    q.appendValue(RecordIdRange("person; DROP TABLE x", start = "a"))
                }
            }
        }
    })
