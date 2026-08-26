package com.surrealdb.kotlin.core.api.data

import com.surrealdb.kotlin.core.api.error.SurrealProtocolException
import com.surrealdb.kotlin.core.api.query.resultRecords
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * One thing a statement answered with. Typed projections read values by path,
 * including nested objects, array indexes, and `[*]` array traversal.
 *
 * Usually that is a record, and [content] is the record as it arrived, for
 * anything the projections do not cover. A `VALUE` projection and `RETURN`
 * answer with values that are not records, and one of those reads only through
 * [decode].
 */
public class Row internal constructor(
    @PublishedApi internal val json: Json,
    private val value: JsonElement,
) {
    /** The record as it arrived. A value that is not a record throws. */
    public val content: JsonObject
        get() =
            value as? JsonObject
                ?: throw SurrealProtocolException(
                    "Expected a record in the result, got $value. Read a value that is not a record with decodeAs().",
                )

    public companion object {
        public fun fromJson(
            json: Json,
            content: JsonObject,
        ): Row = Row(json, content)

        /**
         * The rows of one statement's result, which SurrealDB shapes
         * differently depending on what the statement pointed at: an array for
         * a table target, the record itself under `ONLY` or a record-id target,
         * and null for a statement that matched nothing.
         *
         * A value that is neither, which is what `RETURN` and a `VALUE`
         * projection answer with, is one row too, so a scalar reads back as a
         * scalar through [decode].
         */
        public fun fromResult(
            json: Json,
            result: JsonElement,
        ): List<Row> = resultRecords(result).map { Row(json, it) }
    }

    /** Decode the whole of what this holds, record or value alike. */
    public fun <V> decode(serializer: KSerializer<V>): V = json.decodeFromJsonElement(serializer, value)

    internal fun requireRecord(): JsonObject = content

    /**
     * The value of [field]. A field the record does not carry, or carries as
     * NONE or NULL, is null when the field's type is nullable and throws
     * otherwise, because a record missing a field it was declared with is worth
     * hearing about at the read rather than three frames later.
     */
    public inline operator fun <reified V> get(field: TypedProjection<V>): V = decode(field, serializer())

    /** As [get], for something whose type is not known at the call site. */
    public fun <V> decode(
        projection: Projection,
        serializer: KSerializer<V>,
    ): V {
        val value = resolve(projection.path)
        if (value == null || value is JsonNull) {
            if (serializer.descriptor.isNullable) return json.decodeFromJsonElement(serializer, JsonNull)
            throw NoSuchElementException(
                "No value for '${projection.path.value}' in this row, which holds ${content.keys.sorted()}. " +
                    "Declare the field as nullable to read a missing one as null.",
            )
        }
        return json.decodeFromJsonElement(serializer, value)
    }

    /** True if the record carries [projection] at all, NULL included. */
    public operator fun contains(projection: Projection): Boolean = resolve(projection.path) != null

    override fun toString(): String = value.toString()

    override fun equals(other: Any?): Boolean = other is Row && other.value == value

    override fun hashCode(): Int = value.hashCode()

    private fun resolve(path: FieldPath): JsonElement? =
        content[path.value] ?: walk(
            content,
            path.value
                .replace("[", ".")
                .replace("]", "")
                .split("."),
        )

    private fun walk(
        value: JsonElement,
        steps: List<String>,
    ): JsonElement? {
        val step = steps.firstOrNull() ?: return value
        val rest = steps.drop(1)
        return when {
            value is JsonArray && step == "*" -> JsonArray(value.map { walk(it, rest) ?: JsonNull })
            value is JsonArray -> step.toIntOrNull()?.let(value::getOrNull)?.let { walk(it, rest) }
            value is JsonObject -> value[step]?.let { walk(it, rest) }
            else -> null
        }
    }
}
