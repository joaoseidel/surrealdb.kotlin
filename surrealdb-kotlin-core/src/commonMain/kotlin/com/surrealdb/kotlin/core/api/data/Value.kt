package com.surrealdb.kotlin.core.api.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A database target that can be rendered safely in a statement. Implementations
 * retain their structured parts so callers do not have to inline identifiers.
 */
public interface Target

/**
 * The key half of a record id, in one of the kinds SurrealDB stores.
 *
 * `user:1` and `` user:`1` `` are two records: the first has the integer key
 * `1`, the second the string key `"1"`. The same holds for `user:u'0196…'`
 * against `` user:`0196…` ``. A [RecordKey] says which one is meant, and
 * [toString] spells it the way SurrealQL reads it back.
 *
 * There is no float, boolean or null kind, because SurrealDB has none: a
 * record id written with any of those as its key is a string key.
 */
public sealed interface RecordKey {
    /**
     * A string key. Written bare when it is an identifier and backticked
     * otherwise, so `"1"` prints as `` `1` `` and stays the string key.
     */
    public data class Text(
        public val value: String,
    ) : RecordKey {
        override fun toString(): String = escapeIdent(value)
    }

    /** An integer key, `user:1` or `user:-5`. SurrealDB holds it as an i64. */
    public data class Integer(
        public val value: Long,
    ) : RecordKey {
        override fun toString(): String = value.toString()
    }

    /** A uuid key, written `user:u'0196…'`. */
    public data class Uuid(
        public val value: kotlin.uuid.Uuid,
    ) : RecordKey {
        override fun toString(): String = "u'$value'"
    }

    /**
     * An array key, `user:['a', 1]`, holding what JSON can carry.
     *
     * SurrealDB reads the elements the way it reads a bound value: a string
     * shaped like a uuid becomes a uuid, and one shaped like `table:key`
     * becomes a record. [toString] spells those two the same way, so a key
     * bound as a target and one written as a link name the same record.
     */
    public data class Array(
        public val value: JsonArray,
    ) : RecordKey {
        override fun toString(): String = value.toSurql()
    }

    /**
     * An object key, `user:{ a: 1, b: 'x' }`, holding what JSON can carry.
     * SurrealDB keeps the fields sorted by name, and so does [toString];
     * two objects with the same fields in a different order are one key.
     */
    public data class Object(
        public val value: JsonObject,
    ) : RecordKey {
        override fun toString(): String = value.toSurql()
    }
}

/**
 * A SurrealDB record id (e.g. `user:alice`, `user:1`, `user:u'0196…'`).
 *
 * [table] is held as the text SurrealDB would show between the quotes, and
 * [key] names the record within it by kind. `RecordId("user", "1")` is the
 * record with the string key `"1"` and `RecordId("user", 1)` the one with the
 * integer key `1`; SurrealDB keeps them apart, and so does this type.
 *
 * [toString] spells the record as SurrealQL, quoting a text half that is not
 * a bare identifier. That spelling is also the JSON form: SurrealDB answers
 * with `"user:alice"` for an `id` field and coerces the same string back into
 * a `record` field, so a decoded id can be handed straight back. **The quoting
 * is not optional on the way out**: the server reads `user:a-b` as `user:a`
 * minus `b` and stores `user:a` without complaining.
 */
@Serializable(with = RecordIdSerializer::class)
public data class RecordId(
    public val table: String,
    public val key: RecordKey,
) : Target {
    public constructor(table: String, id: String) : this(table, RecordKey.Text(id))

    public constructor(table: String, id: Long) : this(table, RecordKey.Integer(id))

    public constructor(table: String, id: kotlin.uuid.Uuid) : this(table, RecordKey.Uuid(id))

    override fun toString(): String = "${escapeIdent(table)}:$key"

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
    val reader = RecordIdReader(text)
    val table = reader.readTable()
    val key = reader.readKey()
    reader.expectEnd()
    return RecordId(table, key)
}

private class RecordIdReader(
    private val text: String,
) {
    private var at = 0

    private fun malformed(): Nothing =
        throw SerializationException("Expected a record id of the form 'table:key', got '$text'")

    fun readTable(): String {
        val table =
            when (text.getOrNull(at)) {
                '`' -> {
                    readQuoted('`')
                }

                '⟨' -> {
                    readAngleQuoted()
                }

                else -> {
                    val colon = text.indexOf(':', at)
                    if (colon < 0) malformed()
                    text.substring(at, colon).also { at = colon }
                }
            }
        if (table.isEmpty() || text.getOrNull(at) != ':') malformed()
        at++
        return table
    }

    fun readKey(): RecordKey =
        when {
            at >= text.length -> {
                malformed()
            }

            text[at] == '`' -> {
                RecordKey.Text(readQuoted('`'))
            }

            text[at] == '⟨' -> {
                RecordKey.Text(readAngleQuoted())
            }

            startsUuid() -> {
                RecordKey.Uuid(readUuid())
            }

            text[at] == '[' -> {
                RecordKey.Array(readArray())
            }

            text[at] == '{' -> {
                RecordKey.Object(readObject())
            }

            else -> {
                val bare = text.substring(at)
                at = text.indexOf(':', at).takeIf { it >= 0 } ?: text.length
                INTEGER
                    .matchEntire(bare)
                    ?.value
                    ?.toLongOrNull()
                    ?.let(RecordKey::Integer) ?: RecordKey.Text(bare)
            }
        }

    fun expectEnd() {
        if (at != text.length) throw SerializationException("Trailing text after the key of record id '$text'")
    }

    private fun startsUuid(): Boolean = text[at] == 'u' && text.getOrNull(at + 1).let { it == '\'' || it == '"' }

    private fun readUuid(): kotlin.uuid.Uuid {
        at++
        val raw = readQuoted(text[at])
        return try {
            kotlin.uuid.Uuid.parse(raw)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Expected a uuid key in record id '$text'", e)
        }
    }

    private fun readQuoted(quote: Char): String {
        val read = StringBuilder()
        at++
        while (at < text.length) {
            when (val char = text[at]) {
                '\\' if at + 1 < text.length -> {
                    read.append(text[at + 1].unescaped())
                    at += 2
                }

                quote -> {
                    at++
                    return read.toString()
                }

                else -> {
                    read.append(char)
                    at++
                }
            }
        }
        throw SerializationException("Unterminated $quote in record id '$text'")
    }

    private fun readAngleQuoted(): String {
        val close = text.indexOf('⟩', at)
        if (close < 0) throw SerializationException("Unterminated ⟨ in record id '$text'")
        return text.substring(at + 1, close).also { at = close + 1 }
    }

    private fun readArray(): JsonArray {
        at++
        skipSpace()
        if (text.getOrNull(at) == ']') {
            at++
            return JsonArray(emptyList())
        }
        val elements = mutableListOf<JsonElement>()
        while (true) {
            elements += readValue()
            skipSpace()
            when (text.getOrNull(at)) {
                ',' -> {
                    at++
                }

                ']' -> {
                    at++
                    return JsonArray(elements)
                }

                else -> {
                    throw SerializationException("Unterminated [ in record id '$text'")
                }
            }
            skipSpace()
        }
    }

    private fun readObject(): JsonObject {
        at++
        skipSpace()
        if (text.getOrNull(at) == '}') {
            at++
            return JsonObject(emptyMap())
        }
        val fields = linkedMapOf<String, JsonElement>()
        while (true) {
            val name =
                when (text.getOrNull(at)) {
                    '"', '\'' -> readQuoted(text[at])
                    '`' -> readQuoted('`')
                    else -> readBareUntil(':').trim()
                }
            skipSpace()
            if (text.getOrNull(at) !=
                ':'
            ) {
                throw SerializationException("Expected ':' after '$name' in record id '$text'")
            }
            at++
            fields[name] = readValue()
            skipSpace()
            when (text.getOrNull(at)) {
                ',' -> {
                    at++
                }

                '}' -> {
                    at++
                    return JsonObject(fields)
                }

                else -> {
                    throw SerializationException("Unterminated { in record id '$text'")
                }
            }
            skipSpace()
        }
    }

    private fun readValue(): JsonElement {
        skipSpace()
        return when {
            at >= text.length -> {
                throw SerializationException("Unterminated key in record id '$text'")
            }

            text[at] == '\'' || text[at] == '"' -> {
                JsonPrimitive(readQuoted(text[at]))
            }

            startsUuid() -> {
                at++
                JsonPrimitive(readQuoted(text[at]))
            }

            text[at] == '[' -> {
                readArray()
            }

            text[at] == '{' -> {
                readObject()
            }

            else -> {
                readBareUntil(',', ']', '}').trim().toBareElement()
            }
        }
    }

    private fun readBareUntil(vararg stops: Char): String {
        val start = at
        var depth = 0
        while (at < text.length) {
            val char = text[at]
            when {
                char == '\'' || char == '"' || char == '`' -> {
                    readQuoted(char)
                    continue
                }

                char == '[' || char == '{' -> {
                    depth++
                }

                (char == ']' || char == '}') && depth > 0 -> {
                    depth--
                }

                depth == 0 && char in stops -> {
                    break
                }
            }
            at++
        }
        return text.substring(start, at)
    }

    private fun String.toBareElement(): JsonElement =
        when {
            isEmpty() -> throw SerializationException("Empty element in record id '$text'")
            this == "true" -> JsonPrimitive(true)
            this == "false" -> JsonPrimitive(false)
            this == "NULL" || this == "NONE" -> JsonNull
            INTEGER.matches(this) -> toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(this)
            FLOAT.matches(this) -> JsonPrimitive(removeSuffix("f").toDouble())
            else -> JsonPrimitive(this)
        }

    private fun skipSpace() {
        while (at < text.length && text[at] == ' ') at++
    }
}

private fun Char.unescaped(): Char =
    when (this) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        else -> this
    }

private val INTEGER = Regex("""-?\d+""")
private val FLOAT = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?f?""")
private val UUID_TEXT = Regex("""[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}""")
private val BARE_RECORD = Regex("""[A-Za-z0-9_]+:(?:[A-Za-z0-9_]+|-?\d+|`(?:[^`\\]|\\.)*`)""")
private val BARE_FIELD_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

private fun JsonElement.toSurql(): String =
    when (this) {
        is JsonNull -> {
            "NULL"
        }

        is JsonPrimitive -> {
            if (isString) content.toSurqlElement() else content.toSurqlNumber()
        }

        is JsonArray -> {
            joinToString(", ", "[", "]") { it.toSurql() }
        }

        is JsonObject -> {
            if (isEmpty()) {
                "{  }"
            } else {
                entries
                    .sortedBy { it.key }
                    .joinToString(", ", "{ ", " }") { "${it.key.toSurqlFieldName()}: ${it.value.toSurql()}" }
            }
        }
    }

private fun String.toSurqlNumber(): String =
    if (INTEGER.matches(this) || this == "true" ||
        this == "false"
    ) {
        this
    } else {
        "${this}f"
    }

private fun String.toSurqlElement(): String =
    when {
        UUID_TEXT.matches(this) -> "u'${lowercase()}'"
        BARE_RECORD.matches(this) -> RecordId.parse(this).toString()
        else -> "'" + escapedForQuotes('\'') + "'"
    }

private fun String.toSurqlFieldName(): String =
    if (BARE_FIELD_NAME.matches(this)) this else "\"" + escapedForQuotes('"') + "\""

private fun String.escapedForQuotes(quote: Char): String =
    buildString {
        for (char in this@escapedForQuotes) {
            when (char) {
                '\\' -> append("\\\\")
                quote -> append('\\').append(quote)
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }

/**
 * A range of record ids on a single table (e.g. `user:alice..user:zara`).
 * Both bounds are keys, because a text range and an integer range order
 * differently and the kind has to be said.
 */
public data class RecordIdRange(
    public val table: String,
    public val start: RecordKey? = null,
    public val end: RecordKey? = null,
    public val includeEnd: Boolean = false,
) : Target
