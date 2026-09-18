package com.surrealdb.kotlin.query.api.data

import com.surrealdb.kotlin.core.api.data.RecordKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Write [this] key as the second argument of `type::record`, so SurrealDB
 * reads the kind the caller meant rather than the one it would guess from a
 * bound value. A bound string is otherwise read as a whole record id first:
 * `"a:b"` becomes the key `b`, `"other:1"` the integer `1`, and a string
 * shaped like a uuid becomes a uuid. `<string>` and `<uuid>` say which key
 * is meant, and each cast is parenthesised because a bare one binds to the
 * whole range expression when the key is a bound of one. An integer, array
 * or object arrives over JSON as itself.
 */
internal fun RecordKey.render(
    literal: (String) -> Unit,
    bind: (JsonElement) -> Unit,
) {
    when (this) {
        is RecordKey.Text -> {
            literal("(<string> ")
            bind(JsonPrimitive(value))
            literal(")")
        }

        is RecordKey.Integer -> {
            bind(JsonPrimitive(value))
        }

        is RecordKey.Uuid -> {
            literal("(<uuid> ")
            bind(JsonPrimitive(value.toString()))
            literal(")")
        }

        is RecordKey.Array -> {
            bind(value)
        }

        is RecordKey.Object -> {
            bind(value)
        }
    }
}
