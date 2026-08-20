package com.surrealdb.kotlin.api.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * One record a statement answered with, read through the fields the table
 * declared.
 *
 * ```
 * val adults = db.select(Users).where { age greaterEq 18 }.await()
 * adults.first()[Users.name]     // String, because Users.name is a Field<String>
 * ```
 *
 * The value type comes from the field, so nothing has to be declared twice and
 * `val n: Int = row[Users.name]` does not compile. A caller who wants their own
 * type writes `fun Row.toUser() = User(this[Users.name], ...)` or asks for it
 * up front with [com.surrealdb.kotlin.api.query.Query.decodeAs].
 *
 * A field is looked up by its path, so `field<String>("address.city")` reads
 * the `city` of the `address` object: SurrealDB rebuilds the nesting in what it
 * answers with rather than flattening it. A path ending in `[*]` names no
 * single value and reads as absent.
 *
 * [content] is the record as it arrived, for anything this does not cover.
 */
public class Row internal constructor(
    @PublishedApi internal val json: Json,
    public val content: JsonObject,
) {
    /**
     * The value of [field]. A field the record does not carry, or carries as
     * NONE or NULL, is null when the field's type is nullable and throws
     * otherwise, because a record missing a field it was declared with is worth
     * hearing about at the read rather than three frames later.
     */
    public inline operator fun <reified V> get(field: Field<V>): V = decode(field, serializer())

    /**
     * As [get], for a field whose type is not known at the call site: `reified`
     * refuses a `Field<*>`, and the builders hold their projections as
     * `List<Field<*>>`.
     */
    public fun <V> decode(
        field: Field<*>,
        serializer: KSerializer<V>,
    ): V {
        val value = resolve(field.path)
        if (value == null || value is JsonNull) {
            if (serializer.descriptor.isNullable) return json.decodeFromJsonElement(serializer, JsonNull)
            throw NoSuchElementException(
                "No value for '${field.path}' in this row, which holds ${content.keys.sorted()}. " +
                    "Declare the field as nullable to read a missing one as null.",
            )
        }
        return json.decodeFromJsonElement(serializer, value)
    }

    /** True if the record carries [field] at all, NULL included. */
    public operator fun contains(field: Field<*>): Boolean = resolve(field.path) != null

    override fun toString(): String = content.toString()

    override fun equals(other: Any?): Boolean = other is Row && other.content == content

    override fun hashCode(): Int = content.hashCode()

    private fun resolve(path: String): JsonElement? {
        var value: JsonElement = content
        for (step in path.replace("[", ".").replace("]", "").split(".")) {
            value =
                when (value) {
                    is JsonObject -> value[step]
                    is JsonArray -> step.toIntOrNull()?.let(value::getOrNull)
                    else -> null
                } ?: return null
        }
        return value
    }
}
