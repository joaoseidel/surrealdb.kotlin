package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.query.api.data.Field
import com.surrealdb.kotlin.query.api.data.Table
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * What a `patch { }` block writes through: the JSON Patch (RFC 6902) operations
 * a statement sends, built out of the fields the table declared.
 *
 * A field is named by its declared path and translated to the JSON Pointer the
 * operation needs, so `address.city` is sent as `/address/city` and `tags[0]`
 * as `/tags/0`. A path handed through unchanged writes a top-level key
 * literally named `tags[0]`, with status OK.
 *
 * Two things RFC 6902 promises are not true here, and neither is loud:
 * [replace] of a path the record does not have creates it rather than failing,
 * and [remove] of one does nothing. A misspelt field therefore adds a key
 * rather than reporting anything.
 *
 * The whole patch is atomic. A [test] that does not hold fails the statement
 * and the operations before it do not stick.
 *
 * Like `set { }`, this is the block's parameter rather than its receiver: the
 * receiver is the schema, so a field is named without qualifying it.
 */
public class Patches internal constructor(
    @PublishedApi internal val json: Json,
) {
    internal val ops: MutableList<JsonObject> = mutableListOf()

    /**
     * Set [field] to [value], creating it if the record does not have it. At an
     * array index this inserts and shifts the rest along, which is RFC 6902's
     * meaning; the server rejects an index past the end of the array.
     */
    public inline fun <reified V> add(
        field: Field<V>,
        value: V,
    ) {
        addEncoded(field, json.encodeToJsonElement(serializer<V>(), value))
    }

    /** Append [element] to the array [field], whatever it currently holds. */
    public inline fun <reified E> append(
        field: Field<List<E>>,
        element: E,
    ) {
        appendEncoded(field, json.encodeToJsonElement(serializer<E>(), element))
    }

    /** Remove [field]. A field the record does not have is left alone. */
    public fun remove(field: Field<*>) {
        ops += patchOp("remove", field.pointerForWriting("remove"))
    }

    /**
     * Set [field] to [value].
     *
     * At an array index this is sent as a remove followed by an add, because
     * SurrealDB's own `replace` reads through the path first and cannot read
     * through an index: it answers OK and changes nothing. The pair is part of
     * the same atomic patch, and an index past the end of the array is rejected
     * rather than ignored.
     */
    public inline fun <reified V> replace(
        field: Field<V>,
        value: V,
    ) {
        replaceEncoded(field, json.encodeToJsonElement(serializer<V>(), value))
    }

    /**
     * Fail the whole statement unless [field] currently holds [value]. Nothing
     * else in the patch sticks when it does not, so this is how a patch is made
     * conditional on what it is changing.
     */
    public inline fun <reified V> test(
        field: Field<V>,
        value: V,
    ) {
        testEncoded(field, json.encodeToJsonElement(serializer<V>(), value))
    }

    /** Write what [from] holds to [to] as well. */
    public fun <V> copy(
        from: Field<V>,
        to: Field<V>,
    ) {
        ops += patchOp("copy", to.pointerForWriting("copy"), from = from.pointerForReading("copy"))
    }

    /** Write what [from] holds to [to] and remove [from]. */
    public fun <V> move(
        from: Field<V>,
        to: Field<V>,
    ) {
        ops += patchOp("move", to.pointerForWriting("move"), from = from.pointerForReading("move"))
    }

    @PublishedApi
    internal fun addEncoded(
        field: Field<*>,
        value: JsonElement,
    ) {
        ops += patchOp("add", field.pointerForWriting("add"), value)
    }

    @PublishedApi
    internal fun appendEncoded(
        field: Field<*>,
        value: JsonElement,
    ) {
        ops += patchOp("add", field.pointerForAppending() + "/-", value)
    }

    @PublishedApi
    internal fun replaceEncoded(
        field: Field<*>,
        value: JsonElement,
    ) {
        val pointer = field.pointerForWriting("replace")
        if (field.endsAtAnIndex()) {
            ops += patchOp("remove", pointer)
            ops += patchOp("add", pointer, value)
        } else {
            ops += patchOp("replace", pointer, value)
        }
    }

    @PublishedApi
    internal fun testEncoded(
        field: Field<*>,
        value: JsonElement,
    ) {
        ops += patchOp("test", field.pointerForReading("test"), value)
    }
}

internal fun <S : Table> buildPatches(
    json: Json,
    schema: S,
    block: S.(Patches) -> Unit,
): JsonArray {
    val patches = Patches(json)
    schema.block(patches)
    return JsonArray(patches.ops.toList())
}

private fun patchOp(
    name: String,
    path: String,
    value: JsonElement? = null,
    from: String? = null,
): JsonObject =
    buildJsonObject {
        put("op", name)
        put("path", path)
        from?.let { put("from", it) }
        value?.let { put("value", it) }
    }

private fun Field<*>.pointerForWriting(op: String): String {
    requireNoWildcard(op)
    require(!reachesThroughAnIndex()) {
        "An array index may only be the last segment of a patch path ('$path'): SurrealDB answers " +
            "`$op` through one with OK, having written a key named for the index into every element " +
            "of the array."
    }
    return pointer()
}

private fun Field<*>.pointerForAppending(): String {
    requireNoWildcard("append")
    require(!holdsAnIndex) {
        "`append` cannot name an array index ('$path'): it appends to the array the path names, and " +
            "an index names one element of that array."
    }
    return pointer()
}

private fun Field<*>.pointerForReading(op: String): String {
    requireNoWildcard(op)
    require(!holdsAnIndex) {
        "`$op` reads through its path ('$path'), and SurrealDB cannot read through an array index: " +
            "it answers `/tags/0` with [NONE, NONE] whatever the array holds. `add` and `remove` " +
            "address an element, and `replace` is sent as that pair."
    }
    return pointer()
}

private fun Field<*>.requireNoWildcard(op: String) {
    require(!namesEveryElement) {
        "A patch path cannot name every element ('$path'): a JSON Pointer has no wildcard, and " +
            "SurrealDB answers `$op` on one with OK and no change."
    }
}

private fun Field<*>.pointer(): String = segments().joinToString(separator = "/", prefix = "/")
