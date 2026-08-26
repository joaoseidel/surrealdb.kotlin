package com.surrealdb.kotlin.core.api.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A database target that can be rendered safely in a statement. Implementations
 * retain their structured parts so callers do not have to inline identifiers.
 */
public interface Target

/**
 * A SurrealDB record id (e.g. `user:alice`).
 *
 * Both halves are held as the text SurrealDB would show between the quotes, so
 * `RecordId("user", "a b")` is the record whose key is `a b`. The builder emits
 * the pair via `type::record($_tb, $_id)` with both halves bound, and
 * [toString] spells the same record as SurrealQL, quoting either half that is
 * not a bare identifier.
 *
 * That spelling is also the JSON form. SurrealDB answers with `"user:alice"`
 * for an `id` field and coerces the same string back into a `record` field, so
 * a decoded id can be handed straight back. **The quoting is not optional on
 * the way out**: the server reads `user:a-b` as `user:a` minus `b` and stores
 * `user:a` without complaining.
 *
 * A key SurrealDB shows as a uuid (`user:u'0196...'`) decodes to that uuid's
 * text and names the same record again. An integer key does not: `user:1`
 * decodes to the string `"1"`, which is a different record from the integer
 * `1`, because this type has one key kind and SurrealDB has several.
 */
@Serializable(with = RecordIdSerializer::class)
public data class RecordId(
    public val table: String,
    public val id: String,
) : Target {
    override fun toString(): String = "${escapeIdent(table)}:${escapeIdent(id)}"

    public companion object {
        public fun parse(text: String): RecordId = parseRecordId(text)

        public fun parseOrNull(text: String): RecordId? = runCatching { parseRecordId(text) }.getOrNull()
    }
}

internal object RecordIdSerializer : KSerializer<RecordId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.surrealdb.kotlin.core.api.data.RecordId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: RecordId,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): RecordId = parseRecordId(decoder.decodeString())
}

internal fun parseRecordId(text: String): RecordId {
    val table = readQuotable(text, 0, stopAtColon = true)
    if (text.getOrNull(table.endsAt) != ':') {
        throw SerializationException("Expected a record id of the form 'table:key', got '$text'")
    }

    val key = readQuotable(text, table.endsAt + 1, stopAtColon = false)
    if (key.endsAt != text.length) {
        throw SerializationException("Trailing text after the key of record id '$text'")
    }

    if (table.text.isEmpty() || key.text.isEmpty()) {
        throw SerializationException("Expected a record id of the form 'table:key', got '$text'")
    }

    return RecordId(table.text, UUID_KEY.matchEntire(key.text)?.groupValues?.get(1) ?: key.text)
}

private class RecordIdHalf(
    val text: String,
    val endsAt: Int,
)

private fun readQuotable(
    text: String,
    from: Int,
    stopAtColon: Boolean,
): RecordIdHalf {
    if (text.getOrNull(from) != '`') {
        val colon = text.indexOf(':', from)
        val end = if (stopAtColon && colon >= 0) colon else text.length
        return RecordIdHalf(text.substring(from, end), end)
    }

    val read = StringBuilder()
    var at = from + 1
    while (at < text.length) {
        when (val char = text[at]) {
            '\\' if at + 1 < text.length -> {
                read.append(text[at + 1])
                at += 2
            }

            '`' -> {
                return RecordIdHalf(read.toString(), at + 1)
            }

            else -> {
                read.append(char)
                at++
            }
        }
    }
    throw SerializationException("Unterminated ` in record id '$text'")
}

private val UUID_KEY = Regex("""u'([0-9a-fA-F-]{36})'""")

/**
 * A range of record ids on a single table (e.g. `user:alice..user:zara`).
 */
public data class RecordIdRange(
    public val table: String,
    public val start: String? = null,
    public val end: String? = null,
    public val includeEnd: Boolean = false,
) : Target
