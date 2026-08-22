package com.surrealdb.kotlin

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Guards `api.data` as the bottom of the dependency graph.
 *
 * `Table`, `RecordId` and `RecordIdRange` are the vocabulary every other package speaks — the
 * query DSL binds them, `api.live` carries them in its events, and a user names them directly.
 * That only holds while the package below them depends on nothing: one import of `api.query`
 * here and the vocabulary is entangled with the DSL again, which is the state this package was
 * created to leave.
 */
class ApiDataBoundaryTest :
    ShouldSpec({
        context("com.surrealdb.kotlin.api.data") {
            should("import nothing of our own, so any package can depend on the value types") {
                val dataRoots =
                    listOf(
                        File("../surrealdb-kotlin-core/src/commonMain/kotlin/com/surrealdb/kotlin/api/data"),
                        File("src/commonMain/kotlin/com/surrealdb/kotlin/api/data"),
                    )

                dataRoots.all(File::isDirectory) shouldBe true

                dataRoots
                    .asSequence()
                    .flatMap(File::walkTopDown)
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file
                            .readLines()
                            .mapNotNull { line -> IMPORT.find(line)?.groupValues?.get(1) }
                            .map { imported -> "${file.name} -> $imported" }
                    }.toSortedSet()
                    .shouldBeEmpty()
            }
        }
    }) {
    private companion object {
        val IMPORT = Regex("""^import (com\.surrealdb\.kotlin[A-Za-z0-9_.]*)""")
    }
}
