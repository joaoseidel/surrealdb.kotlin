package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.query.api.data.EdgeTable
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.WalkDirection
import com.surrealdb.kotlin.query.api.data.aliasedAs
import com.surrealdb.kotlin.query.api.data.at
import com.surrealdb.kotlin.query.api.data.first
import com.surrealdb.kotlin.query.api.data.get
import com.surrealdb.kotlin.query.api.data.incoming
import com.surrealdb.kotlin.query.api.data.outgoing
import com.surrealdb.kotlin.query.api.data.walk
import com.surrealdb.kotlin.query.api.query.delete
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private object Chapters : Table("chapter")

private object HasChapter : Table("has_chapter")

private object Authored : Table("authored")

private object Wrote : EdgeTable("wrote")

private object Readers : Table("reader")

private object WeirdEdge : Table("has chapter")

private object Volumes : Table("book") {
    val title by field<String>()
    val author by field<RecordId>()
    val contributors by field<List<RecordId>>()
}

class WalkTest :
    ShouldSpec(
        {
            context("a walk from a named record") {
                should("point a statement at what the last step reached") {
                    val compiled =
                        RecordingContext()
                            .select(RecordId("book", "hobbit").outgoing(HasChapter, Chapters))
                            .compile()

                    compiled.surql shouldContain "FROM (type::record(\$_0, \$_1))->has_chapter->chapter"
                    compiled.bindings.values.map { it.toString() } shouldBe listOf("\"book\"", "\"hobbit\"")
                }

                should("bind both halves of the record rather than writing it into the statement") {
                    val compiled = RecordingContext().select(Volumes["hobbit"].outgoing(HasChapter, Chapters)).compile()

                    compiled.surql shouldNotContain "hobbit"
                }

                should("read the destination table's declaration in where, so a filter stays typed") {
                    val compiled =
                        RecordingContext()
                            .select(RecordId("book", "hobbit").outgoing(HasChapter, Chapters))
                            .where { Volumes.title eq "One" }
                            .compile()

                    compiled.surql shouldContain "WHERE (title = \$_2)"
                }

                should("not be read as one record, however few edges it has") {
                    val compiled = RecordingContext().select(Volumes["hobbit"].outgoing(HasChapter, Chapters)).compile()

                    compiled.surql shouldNotContain "ONLY"
                }

                should("walk the other way round") {
                    val compiled = RecordingContext().select(Volumes["hobbit"].incoming(Authored, Readers)).compile()

                    compiled.surql shouldContain "FROM (type::record(\$_0, \$_1))<-authored<-reader"
                }

                should("carry on from where the last step arrived") {
                    val compiled =
                        RecordingContext()
                            .select(Volumes["hobbit"].outgoing(HasChapter, Chapters).incoming(Authored, Readers))
                            .compile()

                    compiled.surql shouldContain "->has_chapter->chapter<-authored<-reader"
                }

                should("quote a table name SurrealDB would not read back bare") {
                    val compiled = RecordingContext().select(Volumes["hobbit"].outgoing(WeirdEdge, Chapters)).compile()

                    compiled.surql shouldContain "->`has chapter`->chapter"
                }
            }

            context("a walk from the record being read") {
                should("compare against what it reaches") {
                    val compiled =
                        RecordingContext()
                            .select(Volumes)
                            .where { incoming(Authored, Readers) contains RecordId("reader", "ada") }
                            .compile()

                    compiled.surql shouldContain "WHERE (<-authored<-reader CONTAINS type::record(\$_1, \$_2))"
                }

                should("compare against several of them, each bound") {
                    val compiled =
                        RecordingContext()
                            .select(Volumes)
                            .where {
                                incoming(Authored, Readers) containsAny
                                    listOf(RecordId("reader", "ada"), RecordId("reader", "bob"))
                            }.compile()

                    compiled.surql shouldContain
                        "CONTAINSANY [type::record(\$_1, \$_2), type::record(\$_3, \$_4)]"
                }

                should("project under the name of the field that reads it back") {
                    val compiled =
                        RecordingContext()
                            .select(Volumes)
                            .allFieldsAnd(
                                incoming(Authored, Readers).first() aliasedAs Volumes.author,
                                incoming(Authored, Readers) aliasedAs Volumes.contributors,
                            ).compile()

                    compiled.surql shouldContain
                        "SELECT *, <-authored<-reader[0] AS author, <-authored<-reader AS contributors FROM"
                }
            }

            context("an edge table") {
                should("carry the two fields SurrealDB fixes the names of") {
                    val compiled =
                        RecordingContext()
                            .delete(Wrote)
                            .where { (`in` eq RecordId("reader", "ada")) and (out eq RecordId("book", "hobbit")) }
                            .compile()

                    compiled.surql shouldContain
                        "WHERE ((in = type::record(\$_1, \$_2)) AND (out = type::record(\$_3, \$_4)))"
                }
            }

            context("a walk whose direction is decided at runtime") {
                should("go the way it was told, and no other") {
                    val record = RecordId("reader", "ada")

                    record.walk(WalkDirection.Outgoing, Wrote, Volumes).toString() shouldBe
                        "(reader:ada)->wrote->book"
                    record.walk(WalkDirection.Incoming, Wrote, Volumes).toString() shouldBe
                        "(reader:ada)<-wrote<-book"
                }
            }

            context("an index on the last step") {
                should("name one record of what it reached") {
                    val walk = incoming(Authored, Readers).first()

                    walk.toString() shouldBe "<-authored<-reader[0]"
                }

                should("stay where it was put when the walk carries on") {
                    val walk = incoming(HasChapter, Volumes).first().incoming(Authored, Readers)

                    walk.toString() shouldBe "<-has_chapter<-book[0]<-authored<-reader"
                }

                should("refuse a position that is not one") {
                    shouldThrow<IllegalArgumentException> { incoming(Authored, Readers).at(-1) }
                        .message shouldContain "cannot be negative"
                }
            }
        },
    )
