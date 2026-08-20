package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.RecordIdRange
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.TableRecord
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Convert an arbitrary value into a [JsonElement] for binding. Accepts the
 * SDK's typed value wrappers ([Table], [RecordId], [TableRecord],
 * [RecordIdRange]) plus the
 * normal Kotlin types. Throws for anything we can't represent on the wire.
 */
public fun toJson(value: Any?): JsonElement =
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

        is Table<*> -> {
            buildJsonObject {
                put("\$type", JsonPrimitive("table"))
                put("name", JsonPrimitive(value.tableName))
            }
        }

        is RecordId -> {
            buildJsonObject {
                put("\$type", JsonPrimitive("record"))
                put("tb", JsonPrimitive(value.table))
                put("id", JsonPrimitive(value.id))
            }
        }

        is TableRecord<*, *> -> {
            toJson(value.record)
        }

        is RecordIdRange -> {
            buildJsonObject {
                put("\$type", JsonPrimitive("recordrange"))
                put("tb", JsonPrimitive(value.table))
                value.start?.let { put("start", JsonPrimitive(it)) }
                value.end?.let { put("end", JsonPrimitive(it)) }
                put("includeEnd", JsonPrimitive(value.includeEnd))
            }
        }

        is Collection<*> -> {
            JsonArray(value.map { toJson(it) })
        }

        else -> {
            throw IllegalArgumentException(
                "Cannot bind value of type ${value::class.simpleName}: $value — pass a JsonElement, Table, RecordId or a primitive.",
            )
        }
    }

private val IDENT = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

/**
 * Append [target] as the SurrealQL expression naming it. Every statement that
 * takes a target renders it here, so what `select` and `delete` point at is
 * described in exactly one place, and the `when` is exhaustive over [Target]
 * so a new kind of target cannot be added without this seeing it.
 */
internal fun BoundQuery.appendTarget(target: Target): BoundQuery =
    apply {
        when (target) {
            is Table<*> -> {
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

            is TableRecord<*, *> -> {
                appendTarget(target.record)
            }

            is RecordIdRange -> {
                // Range literals in v3 are `tb:start..end` — there's no
                // type::range constructor that accepts table+start+end. Inline
                // the table after validating it as an identifier.
                require(IDENT.matches(target.table)) {
                    "RecordIdRange.table must be a plain identifier (got '${target.table}')"
                }
                appendLiteral(target.table)
                appendLiteral(":")
                target.start?.let { bind(JsonPrimitive(it)) }
                appendLiteral(if (target.includeEnd) "..=" else "..")
                target.end?.let { bind(JsonPrimitive(it)) }
            }
        }
    }

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

/**
 * DSL receiver for assembling a [BoundQuery]:
 * ```
 * val q = surql {
 *     +"SELECT * FROM "
 *     value(Table("user"))
 *     +" WHERE age > "; value(18)
 * }
 * ```
 */
public class SurqlBuilder internal constructor() {
    private val query = BoundQuery()

    /** Append a raw SurrealQL fragment. */
    public operator fun String.unaryPlus() {
        query.appendLiteral(this)
    }

    /** Append a raw SurrealQL fragment (no escaping). */
    public fun literal(text: String): SurqlBuilder = apply { query.appendLiteral(text) }

    /** Append a value, automatically binding non-literal types. */
    public fun value(value: Any?): SurqlBuilder = apply { query.appendValue(value) }

    /** Bind [value] under [name] and emit `$<name>`. */
    public fun param(
        name: String,
        value: Any?,
    ): SurqlBuilder =
        apply {
            query.bindNamed(name, toJson(value))
        }

    /** Splice another [BoundQuery] fragment, merging bindings. */
    public fun fragment(fragment: BoundQuery): SurqlBuilder = apply { query.append(fragment) }

    internal fun build(): BoundQuery = query
}

/** Build a [BoundQuery] using the DSL. */
public fun surql(block: SurqlBuilder.() -> Unit): BoundQuery = SurqlBuilder().apply(block).build()

/**
 * Build a [BoundQuery] from a raw SurrealQL string and a set of pre-named
 * bindings. The string is sent as-is; callers must ensure `$name` placeholders
 * are present for each binding.
 */
public fun surql(
    sql: String,
    vararg bindings: Pair<String, Any?>,
): BoundQuery {
    val builder = BoundQuery().appendLiteral(sql)
    for ((name, value) in bindings) builder.attachBinding(name, toJson(value))
    return builder
}
