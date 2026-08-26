package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.query.api.data.EdgeTable
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.aliasedAs
import com.surrealdb.kotlin.query.api.data.first
import com.surrealdb.kotlin.query.api.data.get
import com.surrealdb.kotlin.query.api.data.incoming
import com.surrealdb.kotlin.query.api.data.outgoing
import com.surrealdb.kotlin.query.api.integrationEndpoint
import com.surrealdb.kotlin.query.api.integrationTestConfig
import com.surrealdb.kotlin.query.api.query.relate
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant

private object GraphBooks : Table("gr_book") {
    val id = recordId()
    val title by field<String>()

    /** Not a column: the name the walk is projected under. */
    val author by field<RecordId>()
    val contributors by field<List<RecordId>>()
    val publishedAt = field<Instant?>("published_at")
}

private object GraphChapters : Table("gr_chapter") {
    val title by field<String>()
    val order by field<Int>()
}

private object GraphReaders : Table("gr_reader") {
    val name by field<String>()
}

private object GraphHasChapter : Table("gr_has_chapter")

private object GraphWrote : EdgeTable("gr_wrote") {
    val role by field<String?>()
}

private object GraphFollows : Table("gr_follows")

private val hobbit = RecordId("gr_book", "hobbit")

private fun onServer(block: suspend (Session) -> Unit) {
    runBlocking {
        val client = Surreal(Surreal.Config(url = integrationEndpoint()))
        val db = client.session()
        try {
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            listOf(GraphBooks, GraphChapters, GraphReaders, GraphHasChapter, GraphWrote, GraphFollows)
                .forEach { db.query(surql("REMOVE TABLE IF EXISTS ${it.tableName}")) }

            db.create(GraphBooks["hobbit"]).set { it[title] = "The Hobbit" }.await()
            db
                .create(GraphChapters["one"])
                .set {
                    it[title] = "An Unexpected Party"
                    it[order] = 2
                }.await()
            db
                .create(GraphChapters["two"])
                .set {
                    it[title] = "Roast Mutton"
                    it[order] = 1
                }.await()
            db.create(GraphReaders["ada"]).set { it[name] = "Ada" }.await()
            db.create(GraphReaders["bob"]).set { it[name] = "bob" }.await()
            db.create(GraphReaders["cyd"]).set { it[name] = "Cyd" }.await()

            db.relate(hobbit, GraphHasChapter, GraphChapters["one"].record).await()
            db.relate(hobbit, GraphHasChapter, GraphChapters["two"].record).await()
            db.relate(GraphReaders["ada"].record, GraphWrote, hobbit).await()
            // A reader following a book as well as a reader, which is what the
            // destination table of a walk has to keep out of a listing of readers.
            db.relate(GraphReaders["bob"].record, GraphFollows, GraphReaders["ada"].record).await()
            db.relate(GraphReaders["bob"].record, GraphFollows, hobbit).await()

            block(db)
        } finally {
            client.close()
        }
    }
}

class GraphIntegrationTest :
    ShouldSpec(
        {
            defaultTestConfig = integrationTestConfig

            context("a walk as a statement target") {
                should("read the records the edges reach") {
                    onServer { db ->
                        val titles =
                            db
                                .select(hobbit.outgoing(GraphHasChapter, GraphChapters))
                                .orderBy(GraphChapters.order.ascending())
                                .await()
                                .map { it[GraphChapters.title] }

                        titles shouldBe listOf("Roast Mutton", "An Unexpected Party")
                    }
                }

                should("keep only the edges whose far end is in the destination table") {
                    onServer { db ->
                        val followed =
                            db
                                .select(GraphReaders["bob"].outgoing(GraphFollows, GraphReaders))
                                .await()
                                .map { it[GraphReaders.name] }

                        followed shouldBe listOf("Ada")
                    }
                }

                should("narrow with a condition over the destination table's fields") {
                    onServer { db ->
                        val titles =
                            db
                                .select(hobbit.outgoing(GraphHasChapter, GraphChapters))
                                .where { GraphChapters.order greater 1 }
                                .await()
                                .map { it[GraphChapters.title] }

                        titles shouldBe listOf("An Unexpected Party")
                    }
                }

                should("take a write as well as a read") {
                    onServer { db ->
                        db
                            .update(hobbit.outgoing(GraphHasChapter, GraphChapters))
                            .set { it[GraphChapters.order] = 9 }
                            .where { GraphChapters.title eq "Roast Mutton" }
                            .await()

                        db
                            .select(hobbit.outgoing(GraphHasChapter, GraphChapters))
                            .where { GraphChapters.title eq "Roast Mutton" }
                            .awaitSingleOrNull()!![GraphChapters.order] shouldBe 9
                    }
                }
            }

            context("a walk beside the record") {
                should("arrive under the field that named it") {
                    onServer { db ->
                        val book =
                            db
                                .select(GraphBooks)
                                .allFieldsAnd(
                                    incoming(GraphWrote, GraphReaders).first() aliasedAs GraphBooks.author,
                                    incoming(GraphWrote, GraphReaders) aliasedAs GraphBooks.contributors,
                                ).awaitSingleOrNull()!!

                        book[GraphBooks.title] shouldBe "The Hobbit"
                        book[GraphBooks.author] shouldBe RecordId("gr_reader", "ada")
                        book[GraphBooks.contributors] shouldBe listOf(RecordId("gr_reader", "ada"))
                    }
                }
            }

            context("a walk in a condition") {
                should("compare against what the record reaches") {
                    onServer { db ->
                        val titles =
                            db
                                .select(GraphBooks)
                                .where { incoming(GraphWrote, GraphReaders) contains RecordId("gr_reader", "ada") }
                                .await()
                                .map { it[GraphBooks.title] }

                        titles shouldBe listOf("The Hobbit")
                    }
                }

                should("compare against several records, none of which is written into the statement") {
                    onServer { db ->
                        val titles =
                            db
                                .select(GraphBooks)
                                .where {
                                    incoming(GraphWrote, GraphReaders) containsAny
                                        listOf(RecordId("gr_reader", "bob"), RecordId("gr_reader", "ada"))
                                }.await()
                                .map { it[GraphBooks.title] }

                        titles shouldBe listOf("The Hobbit")
                    }
                }
            }

            context("an order") {
                should("sort the whole record") {
                    onServer { db ->
                        val names =
                            db.select(GraphReaders).orderBy(GraphReaders.name.ascending()).await().map {
                                it[GraphReaders.name]
                            }

                        names shouldBe listOf("Ada", "Cyd", "bob")
                    }
                }

                should("sort without regard to case when asked to collate") {
                    onServer { db ->
                        val names =
                            db.select(GraphReaders).orderBy(GraphReaders.name.ascending().collate()).await().map {
                                it[GraphReaders.name]
                            }

                        names shouldBe listOf("Ada", "bob", "Cyd")
                    }
                }

                should("sort a projection the statement selected") {
                    onServer { db ->
                        val names =
                            db
                                .select(GraphReaders)
                                .fields(GraphReaders.name)
                                .orderBy(GraphReaders.name.descending())
                                .await()
                                .map { it[GraphReaders.name] }

                        names shouldBe listOf("bob", "Cyd", "Ada")
                    }
                }
            }

            context("a count") {
                should("answer with the number of records a table holds") {
                    onServer { db -> db.count(GraphReaders).await() shouldBe 3L }
                }

                should("narrow with a condition") {
                    onServer { db -> db.count(GraphReaders).where { name eq "Ada" }.await() shouldBe 1L }
                }

                should("count what a walk reached") {
                    onServer { db ->
                        db.count(hobbit.outgoing(GraphHasChapter, GraphChapters)).await() shouldBe 2L
                    }
                }

                should("answer zero rather than nothing when the target matched no record") {
                    onServer { db ->
                        db.count(GraphReaders).where { name eq "nobody" }.await() shouldBe 0L
                        db
                            .count(
                                RecordId("gr_book", "missing").outgoing(GraphHasChapter, GraphChapters),
                            ).await() shouldBe
                            0L
                    }
                }
            }

            context("an edge given fields of its own") {
                should("write them through the relation's declaration") {
                    onServer { db ->
                        db
                            .relate(
                                GraphReaders["cyd"].record,
                                GraphWrote,
                                hobbit,
                            ).content { it[role] = "editor" }
                            .await()

                        db
                            .select(GraphWrote)
                            .where { (`in` eq RecordId("gr_reader", "cyd")) and (out eq hobbit) }
                            .awaitSingleOrNull()!![GraphWrote.role] shouldBe "editor"
                    }
                }
            }

            context("a full-text match") {
                should("read the index defined on the field") {
                    onServer { db ->
                        db.query(
                            surql(
                                "DEFINE ANALYZER OVERWRITE gr_words TOKENIZERS class FILTERS lowercase; " +
                                    "DEFINE INDEX OVERWRITE gr_title_search ON gr_book FIELDS title " +
                                    "FULLTEXT ANALYZER gr_words BM25",
                            ),
                        )

                        db
                            .select(GraphBooks)
                            .where { title matchesFullText "hobbit" }
                            .await()
                            .map { it[GraphBooks.title] } shouldBe listOf("The Hobbit")
                    }
                }
            }

            context("a datetime assigned to a field") {
                should("arrive as a datetime rather than as the text a bound value would be") {
                    val moment = Instant.parse("2024-01-02T03:04:05Z")

                    onServer { db ->
                        db.update(GraphBooks["hobbit"]).set { it[publishedAt] = moment }.await()

                        db.select(hobbit).awaitSingleOrNull()!![GraphBooks.publishedAt] shouldBe moment
                    }
                }
            }

            context("an array assigned as a set") {
                should("hold a value once however often it is included") {
                    onServer { db ->
                        repeat(2) {
                            db
                                .update(
                                    GraphBooks["hobbit"],
                                ).set { it[contributors].include(RecordId("gr_reader", "bob")) }
                                .await()
                        }

                        db.select(hobbit).awaitSingleOrNull()!![GraphBooks.contributors] shouldBe
                            listOf(RecordId("gr_reader", "bob"))
                    }
                }

                should("hold it no longer once excluded") {
                    onServer { db ->
                        db
                            .update(
                                GraphBooks["hobbit"],
                            ).set { it[contributors].include(RecordId("gr_reader", "bob")) }
                            .await()
                        db
                            .update(
                                GraphBooks["hobbit"],
                            ).set { it[contributors].exclude(RecordId("gr_reader", "bob")) }
                            .await()

                        db.select(hobbit).awaitSingleOrNull()!![GraphBooks.contributors] shouldBe emptyList()
                    }
                }
            }
        },
    )
