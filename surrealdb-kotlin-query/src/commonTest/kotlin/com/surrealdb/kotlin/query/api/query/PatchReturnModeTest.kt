package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldEndWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

private val compileOnly =
    object : QueryContext {
        override val json: Json = Json

        override suspend fun query(bound: BoundQuery): JsonElement = error("compile-only")
    }

private fun patchOnPeople() = compileOnly.patch(People, JsonArray(emptyList()))

private val modes: List<Pair<ReturnMode, String>> =
    listOf(
        ReturnMode.None to " RETURN NONE",
        ReturnMode.Before to " RETURN BEFORE",
        ReturnMode.After to " RETURN AFTER",
        ReturnMode.Diff to " RETURN DIFF",
        ReturnMode.Fields(listOf(People.name, People.age)) to " RETURN name, age",
    )

class PatchReturnModeTest :
    ShouldSpec({
        context("PatchQuery.returnMode") {
            modes.forEach { (mode, expected) ->
                should("render$expected") {
                    patchOnPeople().returnMode(mode).compile().surql shouldEndWith expected
                }
            }

            should("come after WHERE, because SurrealQL takes the clauses in that order") {
                val surql =
                    patchOnPeople()
                        .where { age greater 30 }
                        .returnMode(ReturnMode.Diff)
                        .compile()
                        .surql

                surql shouldEndWith " RETURN DIFF"
                surql.indexOf(" WHERE ") shouldBeLessThan surql.indexOf(" RETURN ")
            }

            should("survive a where applied afterwards, so the two are order-independent to the caller") {
                patchOnPeople()
                    .returnMode(ReturnMode.Diff)
                    .where { age greater 30 }
                    .compile()
                    .surql shouldEndWith " RETURN DIFF"
            }
        }
    })
