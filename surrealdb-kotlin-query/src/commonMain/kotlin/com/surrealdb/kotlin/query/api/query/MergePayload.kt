package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.query.api.data.Field
import com.surrealdb.kotlin.query.api.data.Table
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * What a `merge { }` block writes through.
 *
 * A field is named by the path its table declared it with, and the block folds
 * that path into the nested object `MERGE` needs:
 * `it[address.zip] = "99999"` sends `{"address": {"zip": "99999"}}`.
 *
 * SurrealDB reads a key of a merge payload as a key and not as a path, so the
 * dotted form is not an alternative spelling of the same thing:
 * `MERGE {"address.zip": "99999"}` answers OK and leaves the record holding a
 * nested `address` object *and* a top-level key literally named `address.zip`,
 * with the field the caller meant untouched.
 *
 * `MERGE` merges the objects it is given at every level, so naming two fields
 * of one object writes both and leaves the object's other fields alone. An
 * array field is replaced whole rather than appended to.
 *
 * Like `set { }`, this is the block's parameter rather than its receiver: the
 * receiver is the schema, so a field is named without qualifying it.
 */
public class MergePayload internal constructor(
    @PublishedApi internal val json: Json,
) {
    internal val entries: MutableList<Pair<Field<*>, JsonElement>> = mutableListOf()

    /**
     * Merge [value] into [field]. The value type comes from the field, so
     * `it[age] = "30"` fails with "actual type is 'String', but 'Int' was
     * expected".
     *
     * A null sets the field to null; `MERGE` has no way to remove one, which is
     * what `patch { }`'s `remove` is for.
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

internal fun <S : Table> buildMergePayload(
    json: Json,
    schema: S,
    block: S.(MergePayload) -> Unit,
): JsonObject {
    val payload = MergePayload(json)
    schema.block(payload)
    val fields = payload.entries.map { it.first }
    fields.forEach { it.requireMergeable() }
    fields.requireNoneInsideAnother()
    return foldSegments(payload.entries.map { (field, value) -> field.path.value.split('.') to value })
}

private fun Field<*>.requireMergeable() {
    require(!holdsAnIndex) {
        "A merge payload cannot name an array element ('$path'): SurrealDB reads a payload key as a key " +
            "and not as a path, so the path arrives as a top-level key of that name, and folding it into " +
            "the array's own key would replace the array with an object. patch { } addresses an element."
    }
}

private fun List<Field<*>>.requireNoneInsideAnother() {
    forEachIndexed { index, field ->
        take(index).forEach { earlier ->
            require(!earlier.encloses(field) && !field.encloses(earlier)) {
                "'${earlier.path}' and '${field.path}' cannot both be merged: one names the other, " +
                    "and a payload holds one value per key."
            }
        }
    }
}

internal fun Field<*>.encloses(other: Field<*>): Boolean =
    other.path == path || other.path.value.startsWith("${path.value}.")

internal fun foldSegments(entries: List<Pair<List<String>, JsonElement>>): JsonObject =
    JsonObject(
        entries
            .groupBy({ it.first.first() }, { it.first.drop(1) to it.second })
            .mapValues { (_, group) ->
                val (rest, value) = group.first()
                if (rest.isEmpty()) value else foldSegments(group)
            },
    )
