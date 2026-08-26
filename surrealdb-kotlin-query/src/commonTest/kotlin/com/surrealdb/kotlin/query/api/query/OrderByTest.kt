package com.surrealdb.kotlin.query.api.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.string.shouldContain

class OrderByTest :
    ShouldSpec(
        {
            context("an order over the whole record") {
                should("sort on the fields named, first key first") {
                    val compiled =
                        RecordingContext()
                            .select(People)
                            .orderBy(People.age.descending(), People.name.ascending())
                            .compile()

                    compiled.surql shouldContain "SELECT * FROM type::table(\$_0) ORDER BY age DESC, name ASC"
                }

                should("sit before the window, which SurrealDB applies to the sorted rows") {
                    val compiled =
                        RecordingContext()
                            .select(People)
                            .orderBy(People.name.ascending())
                            .start(20)
                            .limit(10)
                            .compile()

                    compiled.surql shouldContain "ORDER BY name ASC START \$_1 LIMIT \$_2"
                }

                should("compare text without regard to case when asked to collate") {
                    val compiled =
                        RecordingContext()
                            .select(
                                People,
                            ).orderBy(People.name.ascending().collate())
                            .compile()

                    compiled.surql shouldContain "ORDER BY name COLLATE ASC"
                }

                should("read the text as a number when asked to") {
                    val compiled =
                        RecordingContext()
                            .select(
                                People,
                            ).orderBy(People.name.descending().numeric())
                            .compile()

                    compiled.surql shouldContain "ORDER BY name NUMERIC DESC"
                }

                should("refuse to collate and read as a number at once, because SurrealDB takes one") {
                    shouldThrow<IllegalArgumentException> {
                        People.name
                            .ascending()
                            .collate()
                            .numeric()
                    }.message shouldContain "COLLATE and NUMERIC at once"
                }
            }

            context("an order over a projection") {
                should("sort on a field that was selected") {
                    val compiled =
                        RecordingContext()
                            .select(People)
                            .fields(People.name, People.age)
                            .orderBy(People.age.descending())
                            .compile()

                    compiled.surql shouldContain "SELECT name, age FROM type::table(\$_0) ORDER BY age DESC"
                }

                should("refuse a field that was not, because SurrealDB refuses the statement") {
                    val message =
                        shouldThrow<IllegalArgumentException> {
                            RecordingContext()
                                .select(People)
                                .fields(People.name)
                                .orderBy(People.age.descending())
                                .compile()
                        }.message

                    message shouldContain "'age' is ordered on but not selected"
                }

                should("refuse a leaf of a group that was selected, which SurrealDB refuses too") {
                    shouldThrow<IllegalArgumentException> {
                        RecordingContext()
                            .select(Contacts)
                            .fields(Contacts.address)
                            .orderBy(Contacts.address.city.ascending())
                            .compile()
                    }
                }

                should("take any field when the whole record was selected beside the projections") {
                    val compiled =
                        RecordingContext()
                            .select(People)
                            .allFieldsAnd(People.name)
                            .orderBy(People.age.descending())
                            .compile()

                    compiled.surql shouldContain "SELECT *, name FROM type::table(\$_0) ORDER BY age DESC"
                }
            }
        },
    )
