package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Projection
import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.RecordIdRange
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.TableRecord
import com.surrealdb.kotlin.api.data.Target
import com.surrealdb.kotlin.api.data.escapeIdent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Convert an arbitrary value into a [JsonElement] for binding. Accepts the
 * normal Kotlin types. Targets are SurrealQL expressions rather than JSON
 * values, so callers pass those to typed builders. Throws for anything the
 * JSON transport cannot represent.
 */
internal fun toJson(value: Any?): JsonElement =
    when (value) {
        null -> {
            kotlinx.serialization.json.JsonNull
        }

        is JsonElement -> {
            value
        }

        is String -> {
            JsonPrimitive(value)
        }

        is Boolean -> {
            JsonPrimitive(value)
        }

        is Number -> {
            JsonPrimitive(value)
        }

        is Collection<*> -> {
            JsonArray(value.map { toJson(it) })
        }

        else -> {
            throw IllegalArgumentException(
                "Cannot bind '$value' as JSON. Its type is ${value::class.simpleName}; pass a JsonElement or a primitive.",
            )
        }
    }

/**
 * Append [target] as the SurrealQL expression naming it. Every statement that
 * takes a target renders it here, so what `select` and `delete` point at is
 * described in exactly one place, and the `when` is exhaustive over [Target]
 * so a new kind of target cannot be added without this seeing it.
 */
internal fun BoundQuery.appendTarget(target: Target): BoundQuery =
    apply {
        when (target) {
            is Table -> {
                appendLiteral("type::table(")
                bind(JsonPrimitive(target.tableName))
                appendLiteral(")")
            }

            is RecordId -> {
                // SurrealDB v3 calls this `type::record`; the older `type::thing`
                // is gone. We bind both halves so callers can't inject.
                appendLiteral("type::record(")
                bind(JsonPrimitive(target.table))
                appendLiteral(", ")
                bind(JsonPrimitive(target.id))
                appendLiteral(")")
            }

            is TableRecord<*> -> {
                appendTarget(target.record)
            }

            is RecordIdRange -> {
                appendLiteral("type::record(")
                bind(JsonPrimitive(target.table))
                appendLiteral(", ")
                target.start?.let { bind(JsonPrimitive(it)) }
                appendLiteral(if (target.includeEnd) "..=" else "..")
                target.end?.let { bind(JsonPrimitive(it)) }
                appendLiteral(")")
            }

            else -> {
                throw IllegalArgumentException("Unsupported query target: ${target::class.simpleName}")
            }
        }
    }

internal fun Target.matchesAtMostOneRecord(): Boolean =
    when (this) {
        is RecordId, is TableRecord<*> -> true
        is Table, is RecordIdRange -> false
        else -> false
    }

internal fun BoundQuery.appendStatementTarget(
    target: Target,
    forceOnly: Boolean,
): BoundQuery =
    apply {
        if (forceOnly || target.matchesAtMostOneRecord()) appendLiteral("ONLY ")
        appendTarget(target)
    }

/**
 * SurrealDB answers `SELECT tags[0]` with `{"tags": "cs"}`: it drops the index
 * and puts the value on the parent key, where the declaration does not name it
 * and a second index into the same array overwrites the first.
 */
internal fun BoundQuery.appendProjections(projections: List<Projection>): BoundQuery =
    apply { appendLiteral(projections.joinToString(", ") { it.keptAtItsOwnPath() }) }

private fun Projection.keptAtItsOwnPath(): String =
    if (holdsAnIndex) "$path AS ${escapeIdent(path.value)}" else path.value

/**
 * Append [value] to the query, choosing the right SurrealQL expression based
 * on the value's type. Strings/numbers/booleans become bound parameters; a
 * [Target] expands into the SurrealQL expression naming it.
 *
 * This is the value path — expression operands and function arguments, where
 * anything bindable is legitimate. A statement's target goes through
 * [appendTarget] instead.
 */
internal fun BoundQuery.appendValue(value: Any?): BoundQuery =
    apply {
        when (value) {
            is Target -> appendTarget(value)
            else -> bind(toJson(value))
        }
    }

internal fun surql(sql: String): BoundQuery = BoundQuery().appendLiteral(sql)
