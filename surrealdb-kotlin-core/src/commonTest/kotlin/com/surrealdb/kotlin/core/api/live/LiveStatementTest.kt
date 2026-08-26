package com.surrealdb.kotlin.core.api.live

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class LiveStatementTest :
    ShouldSpec(
        {
            context("toLiveStatement") {
                context("a table name") {
                    should("become a LIVE SELECT over that table, so the common case needs no SurrealQL") {
                        toLiveStatement("book") shouldBe "LIVE SELECT * FROM book"
                    }

                    should("accept a record id, which the server takes in the same position") {
                        toLiveStatement("book:one") shouldBe "LIVE SELECT * FROM book:one"
                    }

                    should("ignore the whitespace around it") {
                        toLiveStatement("  book  ") shouldBe "LIVE SELECT * FROM book"
                    }
                }

                context("a SELECT statement") {
                    should("gain the LIVE prefix, since a WHERE filter is unreachable through the live RPC method") {
                        toLiveStatement("SELECT * FROM book WHERE pages > 5") shouldBe
                            "LIVE SELECT * FROM book WHERE pages > 5"
                    }

                    should("be recognised whatever its case, because SurrealQL keywords are not case sensitive") {
                        toLiveStatement("select * from book") shouldBe "LIVE select * from book"
                    }
                }

                context("a statement that is already LIVE") {
                    should("pass through untouched, rather than gain a second LIVE") {
                        toLiveStatement("LIVE SELECT * FROM book") shouldBe "LIVE SELECT * FROM book"
                    }

                    should("be recognised whatever its case") {
                        toLiveStatement("live select * from book") shouldBe "live select * from book"
                    }
                }

                context("a trailing semicolon") {
                    should("be dropped, so the statement stays a single one the server can answer with one id") {
                        toLiveStatement("LIVE SELECT * FROM book;") shouldBe "LIVE SELECT * FROM book"
                    }

                    should("be dropped from a table name written as a statement") {
                        toLiveStatement("book ;") shouldBe "LIVE SELECT * FROM book"
                    }
                }

                context("a spec it cannot read") {
                    should(
                        "reject it rather than guess, because a wrong guess is a subscription that silently never fires",
                    ) {
                        val failure =
                            shouldThrow<IllegalArgumentException> {
                                toLiveStatement("DELETE FROM book")
                            }

                        failure.message.shouldContain("DELETE FROM book")
                    }

                    should("name the three forms it does accept, so the caller can fix it from the message alone") {
                        val failure =
                            shouldThrow<IllegalArgumentException> {
                                toLiveStatement("FROM book")
                            }

                        failure.message.shouldContain("SELECT * FROM book WHERE pages > 5")
                    }

                    should("reject a blank spec, which would otherwise become a LIVE SELECT over no table at all") {
                        shouldThrow<IllegalArgumentException> { toLiveStatement("   ") }
                    }
                }
            }
        },
    )
