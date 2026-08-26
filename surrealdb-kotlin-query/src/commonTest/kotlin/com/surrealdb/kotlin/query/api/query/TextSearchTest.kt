package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.query.api.data.Table
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private object Papers : Table("paper") {
    val title by field<String>()
    val abstract by field<String?>()
}

class TextSearchTest :
    ShouldSpec(
        {
            context("a case-folded contains") {
                should("fold both sides, so a lowercase search finds a capitalised value") {
                    val compiled =
                        RecordingContext()
                            .select(
                                Papers,
                            ).where { title containsIgnoringCase "Ada" }
                            .compile()

                    compiled.surql shouldContain
                        "WHERE (string::lowercase(title ?? '') CONTAINS string::lowercase(\$_1))"
                }

                should("bind the term rather than writing it into the statement") {
                    val compiled =
                        RecordingContext()
                            .select(
                                Papers,
                            ).where { title containsIgnoringCase "Ada" }
                            .compile()

                    compiled.surql shouldNotContain "Ada"
                }

                should("read a field the record does not carry as empty, rather than dropping the record") {
                    val compiled =
                        RecordingContext().select(Papers).where { abstract containsIgnoringCase "dragon" }.compile()

                    compiled.surql shouldContain "string::lowercase(abstract ?? '')"
                }

                should("be a different operator from the full-text one, which reads an index") {
                    val folded = RecordingContext().select(Papers).where { title containsIgnoringCase "ada" }.compile()
                    val indexed = RecordingContext().select(Papers).where { title matchesFullText "ada" }.compile()

                    folded.surql shouldContain "CONTAINS"
                    indexed.surql shouldContain "@@"
                }
            }
        },
    )
