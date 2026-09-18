package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordIdRange
import com.surrealdb.kotlin.core.api.data.RecordKey
import com.surrealdb.kotlin.core.api.query.BoundQuery
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlin.uuid.Uuid

private val alice = RecordKey.Text("alice")
private val zara = RecordKey.Text("zara")

/**
 * SurrealDB v3 refuses a parameter where it expects a record-id key, so the
 * literal form `tb:$start..$end` does not parse. A cast on a bound binds to
 * the whole range unless parenthesised: `<string> $a..<string> $b` matches
 * nothing and `<int> $a..<int> $b` fails to cast `-10..2`.
 */
class RecordIdRangeTest :
    ShouldSpec(
        {
            context("a RecordIdRange rendered into a query") {
                should("bind the table and both bounds, so none of them can carry SurrealQL into the string") {
                    val q = BoundQuery()

                    q.appendValue(RecordIdRange("person", start = alice, end = zara))

                    q.surql shouldBe "type::record(\$_0, (<string> \$_1)..(<string> \$_2))"
                    q.bindings["_0"] shouldBe JsonPrimitive("person")
                    q.bindings["_1"] shouldBe JsonPrimitive("alice")
                    q.bindings["_2"] shouldBe JsonPrimitive("zara")
                }

                should("close the range inclusively when includeEnd is set") {
                    val q = BoundQuery()

                    q.appendValue(RecordIdRange("person", start = alice, end = zara, includeEnd = true))

                    q.surql shouldBe "type::record(\$_0, (<string> \$_1)..=(<string> \$_2))"
                }

                should("close inclusively with no start too, because the two bounds are independent") {
                    val q = BoundQuery()

                    q.appendValue(RecordIdRange("person", end = zara, includeEnd = true))

                    q.surql shouldBe "type::record(\$_0, ..=(<string> \$_1))"
                }

                should("leave the open side empty when only one bound is given") {
                    val fromAlice = BoundQuery()
                    val untilZara = BoundQuery()

                    fromAlice.appendValue(RecordIdRange("person", start = alice))
                    untilZara.appendValue(RecordIdRange("person", end = zara))

                    fromAlice.surql shouldBe "type::record(\$_0, (<string> \$_1)..)"
                    untilZara.surql shouldBe "type::record(\$_0, ..(<string> \$_1))"
                }

                should("render an unbounded range, which names every key the table has") {
                    val q = BoundQuery()

                    q.appendValue(RecordIdRange("person"))

                    q.surql shouldBe "type::record(\$_0, ..)"
                    q.bindings["_0"] shouldBe JsonPrimitive("person")
                }

                should("take a table name that is not an identifier, because it is bound rather than inlined") {
                    val q = BoundQuery()

                    q.appendValue(RecordIdRange("person; DROP TABLE x", start = RecordKey.Text("a")))

                    q.surql shouldBe "type::record(\$_0, (<string> \$_1)..)"
                    q.bindings["_0"] shouldBe JsonPrimitive("person; DROP TABLE x")
                }

                should("bind integer bounds bare, so the range runs over integer keys and not their digits as text") {
                    val q = BoundQuery()

                    q.appendValue(RecordIdRange("person", start = RecordKey.Integer(0), end = RecordKey.Integer(100)))

                    q.surql shouldBe "type::record(\$_0, \$_1..\$_2)"
                    q.bindings["_1"] shouldBe JsonPrimitive(0L)
                    q.bindings["_2"] shouldBe JsonPrimitive(100L)
                }

                should("cast uuid bounds, so the range runs over uuid keys") {
                    val q = BoundQuery()
                    val low = Uuid.parse("00000000-0000-0000-0000-000000000000")
                    val high = Uuid.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")

                    q.appendValue(
                        RecordIdRange(
                            "person",
                            start = RecordKey.Uuid(low),
                            end = RecordKey.Uuid(high),
                            includeEnd = true,
                        ),
                    )

                    q.surql shouldBe "type::record(\$_0, (<uuid> \$_1)..=(<uuid> \$_2))"
                    q.bindings["_1"] shouldBe JsonPrimitive(low.toString())
                }

                should("bind array bounds as JSON, which SurrealDB reads as array keys") {
                    val q = BoundQuery()
                    val a = buildJsonArray { add("a") }
                    val a2 =
                        buildJsonArray {
                            add("a")
                            add(2)
                        }

                    q.appendValue(
                        RecordIdRange(
                            "person",
                            start = RecordKey.Array(a),
                            end = RecordKey.Array(a2),
                            includeEnd = true,
                        ),
                    )

                    q.surql shouldBe "type::record(\$_0, \$_1..=\$_2)"
                    q.bindings["_1"] shouldBe a
                    q.bindings["_2"] shouldBe a2
                }
            }
        },
    )
