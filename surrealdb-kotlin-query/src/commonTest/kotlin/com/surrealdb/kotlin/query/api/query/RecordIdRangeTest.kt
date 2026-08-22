package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordIdRange
import com.surrealdb.kotlin.core.api.query.BoundQuery
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * SurrealDB v3 refuses a parameter where it expects a record-id key, so the
 * literal form `tb:$start..$end` does not parse.
 */
class RecordIdRangeTest :
    ShouldSpec({
        context("a RecordIdRange rendered into a query") {
            should("bind the table and both bounds, so none of them can carry SurrealQL into the string") {
                val q = BoundQuery()

                q.appendValue(RecordIdRange("person", start = "alice", end = "zara"))

                q.surql shouldBe "type::record(\$_0, \$_1..\$_2)"
                q.bindings["_0"] shouldBe JsonPrimitive("person")
                q.bindings["_1"] shouldBe JsonPrimitive("alice")
                q.bindings["_2"] shouldBe JsonPrimitive("zara")
            }

            should("close the range inclusively when includeEnd is set") {
                val q = BoundQuery()

                q.appendValue(RecordIdRange("person", start = "alice", end = "zara", includeEnd = true))

                q.surql shouldBe "type::record(\$_0, \$_1..=\$_2)"
            }

            should("close inclusively with no start too, because the two bounds are independent") {
                val q = BoundQuery()

                q.appendValue(RecordIdRange("person", end = "zara", includeEnd = true))

                q.surql shouldBe "type::record(\$_0, ..=\$_1)"
            }

            should("leave the open side empty when only one bound is given") {
                val fromAlice = BoundQuery()
                val untilZara = BoundQuery()

                fromAlice.appendValue(RecordIdRange("person", start = "alice"))
                untilZara.appendValue(RecordIdRange("person", end = "zara"))

                fromAlice.surql shouldBe "type::record(\$_0, \$_1..)"
                untilZara.surql shouldBe "type::record(\$_0, ..\$_1)"
            }

            should("render an unbounded range, which names every key the table has") {
                val q = BoundQuery()

                q.appendValue(RecordIdRange("person"))

                q.surql shouldBe "type::record(\$_0, ..)"
                q.bindings["_0"] shouldBe JsonPrimitive("person")
            }

            should("take a table name that is not an identifier, because it is bound rather than inlined") {
                val q = BoundQuery()

                q.appendValue(RecordIdRange("person; DROP TABLE x", start = "a"))

                q.surql shouldBe "type::record(\$_0, \$_1..)"
                q.bindings["_0"] shouldBe JsonPrimitive("person; DROP TABLE x")
            }
        }
    })
