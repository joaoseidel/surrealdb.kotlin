package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.query.api.data.Table
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

private object Articles : Table("article") {
    val title by field<String>()
    val views by field<Int>()
    val tags by field<List<String>>()
    val firstTag = field<String>("tags[0]")
    val everyTag = field<List<String>>("tags[*]")
    val city = field<String>("venue.city")
    val subtitle by field<String>()
    val firstAuthorName = field<String>("authors[0].name")
    val firstRow = field<List<String>>("matrix[0]")
}

private fun opsOf(build: Articles.(Patches) -> Unit): JsonArray =
    RecordingContext()
        .patch(Articles, build)
        .compile()
        .bindings
        .getValue("_1")
        .jsonArray

class PatchesTest :
    ShouldSpec({
        context("a patch block") {
            should("send a declared path as the JSON Pointer the operation needs") {
                opsOf { it.replace(city, "Boston") }.toString() shouldBe
                    """[{"op":"replace","path":"/venue/city","value":"Boston"}]"""
            }

            should("send an array index as a pointer segment, not as the path the field declared") {
                opsOf { it.remove(firstTag) }.toString() shouldBe """[{"op":"remove","path":"/tags/0"}]"""
            }

            should(
                "send a replace at an index as a remove and an add, because the server's replace does nothing there",
            ) {
                opsOf { it.replace(firstTag, "cs") }.toString() shouldBe
                    """[{"op":"remove","path":"/tags/0"},{"op":"add","path":"/tags/0","value":"cs"}]"""
            }

            should("send one replace for a path with no index in it") {
                opsOf { it.replace(title, "SICP") }.single().toString() shouldContain """"op":"replace""""
            }

            should("append at the end of the array rather than at an index") {
                opsOf { it.append(tags, "cs") }.toString() shouldBe
                    """[{"op":"add","path":"/tags/-","value":"cs"}]"""
            }

            should("name both ends of a copy, so the server has a from as well as a path") {
                opsOf { it.copy(from = title, to = subtitle) }.toString() shouldBe
                    """[{"op":"copy","path":"/subtitle","from":"/title"}]"""
            }

            should("keep the operations in the order the block wrote them, because a patch is applied in order") {
                opsOf {
                    it.test(title, "SICP")
                    it.replace(views, 1)
                }.map { it.toString().substringAfter("\"op\":\"").substringBefore("\"") } shouldBe
                    listOf("test", "replace")
            }

            should("encode each value by the type its field declared") {
                opsOf { it.add(views, 1) }.toString() shouldBe """[{"op":"add","path":"/views","value":1}]"""
            }

            should("send an empty array for a block that names nothing, which the server answers untouched") {
                opsOf { }.toString() shouldBe "[]"
            }

            should("bind the operations, so nothing a caller supplied reaches the SurrealQL") {
                val compiled =
                    RecordingContext()
                        .patch(
                            Articles,
                        ) { it.replace(title, "'); DROP TABLE article; --") }
                        .compile()

                compiled.surql shouldBe "UPDATE type::table(\$_0) PATCH \$_1"
            }

            should(
                "refuse a test at an array index, because the server reads [NONE, NONE] there whatever the array holds",
            ) {
                val failure = shouldThrow<IllegalArgumentException> { opsOf { it.test(firstTag, "cs") } }

                failure.message.toString() shouldContain "`test` reads through its path ('tags[0]')"
            }

            should("refuse a copy out of an array index, for the same reason") {
                val failure =
                    shouldThrow<IllegalArgumentException> { opsOf { it.copy(from = firstTag, to = subtitle) } }

                failure.message.toString() shouldContain "`copy` reads through its path ('tags[0]')"
            }

            should(
                "refuse an index before the last segment, because the server writes into every element of the array",
            ) {
                val failure = shouldThrow<IllegalArgumentException> { opsOf { it.replace(firstAuthorName, "Ada") } }

                failure.message.toString() shouldContain
                    "may only be the last segment of a patch path ('authors[0].name')"
            }

            should("refuse a path naming every element, because a JSON Pointer has no wildcard") {
                val failure = shouldThrow<IllegalArgumentException> { opsOf { it.remove(everyTag) } }

                failure.message.toString() shouldContain "cannot name every element ('tags[*]')"
            }

            should("refuse an append at an array index, because an append reaches the end of the array a path names") {
                val failure = shouldThrow<IllegalArgumentException> { opsOf { it.append(firstRow, "cs") } }

                failure.message.toString() shouldContain "`append` cannot name an array index ('matrix[0]')"
            }
        }
    })
