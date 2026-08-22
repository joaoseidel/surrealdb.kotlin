package com.surrealdb.kotlin.api.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * One JSON record returned by the server. Typed projections read values by
 * path, including nested objects, array indexes, and `[*]` array traversal.
 *
 * [content] is the record as it arrived, for anything this does not cover.
 */
public class Row internal constructor(
    @PublishedApi internal val json: Json,
    public val content: JsonObject,
) {
    public companion object {
        public fun fromJson(
            json: Json,
            content: JsonObject,
        ): Row = Row(json, content)
    }

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

    override fun toString(): String = content.toString()

    override fun equals(other: Any?): Boolean = other is Row && other.content == content

    override fun hashCode(): Int = content.hashCode()

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
