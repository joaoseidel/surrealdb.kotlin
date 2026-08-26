package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.query.api.data.Table
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

class CountTest :
    ShouldSpec(
        {
            context("a count over a table") {
                should("group the whole target into one row, which is where the number is") {
                    val compiled = RecordingContext().count(People).compile()

                    compiled.surql shouldBe "SELECT count() FROM type::table(\$_0) GROUP ALL"
                }

                should("narrow with the same conditions a select takes") {
                    val compiled = RecordingContext().count(People).where { age greaterEq 18 }.compile()

                    compiled.surql shouldContain "FROM type::table(\$_0) WHERE (age >= \$_1) GROUP ALL"
                }

                should("narrow by nothing at all when the filter assembled no condition") {
                    val compiled = RecordingContext().count(People).where(null).compile()

                    compiled.surql shouldNotContain "WHERE"
                }

                should("read the number back") {
                    val context = RecordingContext(result = Json.parseToJsonElement("""[{"count":7}]"""))

                    context.count(People).await() shouldBe 7L
                }

                should("answer zero for a target that matched nothing, which answers with no rows at all") {
                    val context = RecordingContext(result = Json.parseToJsonElement("""[]"""))

                    context.count(People).await() shouldBe 0L
                }
            }

            context("a count over something other than a table") {
                should("never read one record, because a count is a question about a set") {
                    val compiled = RecordingContext().count(RecordId("person", "ada")).compile()

                    compiled.surql shouldNotContain "ONLY"
                }
            }
        },
    )
