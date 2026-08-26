package com.surrealdb.kotlin.query

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class QueryDataBoundaryTest :
    ShouldSpec(
        {
            context("com.surrealdb.kotlin.query.api.data") {
                should("not import query builders") {
                    val dataRoot = File("src/commonMain/kotlin/com/surrealdb/kotlin/query/api/data")

                    dataRoot.isDirectory shouldBe true

                    dataRoot
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .flatMap { file ->
                            file
                                .readLines()
                                .mapNotNull { line -> QUERY_IMPORT.find(line)?.groupValues?.get(1) }
                                .map { imported -> "${file.name} -> $imported" }
                        }.toSortedSet()
                        .shouldBeEmpty()
                }
            }
        },
    ) {
    private companion object {
        val QUERY_IMPORT = Regex("""^import (com\.surrealdb\.kotlin\.query\.api\.query[A-Za-z0-9_.]*)""")
    }
}
