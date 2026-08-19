package com.surrealdb.kotlin.api.query

import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Typed-decode terminal operations for each query builder. Each calls the
 * builder's `await()` (which returns the unwrapped first-statement result)
 * and decodes it via the context's [kotlinx.serialization.json.Json].
 */

public suspend inline fun <reified T> SelectQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> CreateQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> UpsertQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> UpdateQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> MergeQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> PatchQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> DeleteQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> RelateQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> InsertQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> InsertRelationQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())

public suspend inline fun <reified T> RunQuery.awaitAs(): T = context.json.decodeFromJsonElement(await())
