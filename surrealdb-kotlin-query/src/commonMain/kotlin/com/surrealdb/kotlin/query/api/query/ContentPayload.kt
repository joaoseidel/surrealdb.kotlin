package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.query.api.data.Field
import com.surrealdb.kotlin.query.api.data.Table
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * What a `content { }` block writes through.
 *
 * A field is named by the path its table declared it with, and the block folds
 * that path into the nested object `CONTENT` / `INSERT` needs:
 * `it[address.zip] = "99999"` sends `{"address": {"postal_code": "99999"}}`.
 *
 * Like `set { }` and `merge { }`, this is the block's parameter rather than its
 * receiver: the receiver is the schema, so a field is named without qualifying it.
 */
public class ContentPayload internal constructor(
    @PublishedApi internal val json: Json,
) {
    internal val entries: MutableList<Pair<Field<*>, JsonElement>> = mutableListOf()

    /**
     * Set [field] to [value] in the content payload. The value type comes from the field, so
     * `it[age] = "30"` fails with "actual type is 'String', but 'Int' was
     * expected".
     */
    public inline operator fun <reified V> set(
        field: Field<V>,
        value: V,
    ) {
        record(field, json.encodeToJsonElement(serializer<V>(), value))
    }

    @PublishedApi
    internal fun record(
        field: Field<*>,
        value: JsonElement,
    ) {
        entries += field to value
    }
}

internal fun <S : Table> buildContentPayload(
    json: Json,
    schema: S,
    block: S.(ContentPayload) -> Unit,
): JsonObject? {
    val payload = ContentPayload(json)
    schema.block(payload)
    if (payload.entries.isEmpty()) return null
    val fields = payload.entries.map { it.first }
    fields.forEach { it.requireContentable() }
    fields.requireNoneInsideAnother()
    return foldSegments(payload.entries.map { (field, value) -> field.path.value.split('.') to value })
}

private fun Field<*>.requireContentable() {
    require(!holdsAnIndex) {
        "A content payload cannot name an array element ('$path'): SurrealDB reads a payload key as a key " +
            "and not as a path, so the path arrives as a top-level key of that name, and folding it into " +
            "the array's own key would replace the array with an object. patch { } addresses an element."
    }
}

private fun List<Field<*>>.requireNoneInsideAnother() {
    forEachIndexed { index, field ->
        take(index).forEach { earlier ->
            require(!earlier.encloses(field) && !field.encloses(earlier)) {
                "'${earlier.path}' and '${field.path}' cannot both be set: one names the other, " +
                    "and a payload holds one value per key."
            }
        }
    }
}
