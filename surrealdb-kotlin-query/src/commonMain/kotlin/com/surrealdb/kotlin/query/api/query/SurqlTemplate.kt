package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.query.api.data.SurqlDsl
import kotlinx.serialization.json.JsonElement

/**
 * Builds SurrealQL with values registered as query parameters.
 *
 * ```
 * val minimumAge = 18
 * val query = surqlTemplate {
 *     "SELECT * FROM person WHERE age >= ${bind(minimumAge)}"
 * }
 * ```
 *
 * [bind] accepts values. A table or field name cannot be parameterised by SurrealDB, so use the
 * typed query builders when an identifier varies. The JSON RPC transport can decode a bound string
 * that looks like a record id as a record and discard the rest of the string. Only a CBOR transport
 * preserves that string.
 */
@SurqlDsl
public class SurqlTemplate internal constructor() {
    private val registered = linkedMapOf<String, JsonElement>()

    public fun bind(value: Any?): String {
        val name = "_${registered.size}"
        registered[name] = toJson(value)
        return "\$$name"
    }

    /**
     * A record id as SurrealQL, with both halves bound: `type::record($t, $i)`.
     *
     * A record cannot be bound whole. The JSON transport carries no record
     * type, so a bound `book:hobbit` reaches SurrealDB as a string and
     * `WHERE out = $book` matches nothing — a delete would then report success
     * having removed nothing. Binding each half keeps the value the caller's
     * and the expression a record link.
     */
    public fun record(id: RecordId): String = "type::record(${bind(id.table)}, ${bind(id.id)})"

    internal fun build(block: SurqlTemplate.() -> String): BoundQuery {
        val sql = block()
        registered.keys.forEach { sql.validatePlaceholder(it, registered.keys) }
        return BoundQuery().appendLiteral(sql).apply {
            registered.forEach { (name, value) -> attachBinding(name, value) }
        }
    }
}

public fun surqlTemplate(block: SurqlTemplate.() -> String): BoundQuery = SurqlTemplate().build(block)

private fun String.validatePlaceholder(
    name: String,
    registeredNames: Set<String>,
) {
    val placeholder = "\$$name"
    var searchFrom = 0
    while (true) {
        val start = indexOf(placeholder, searchFrom)
        if (start < 0) return
        val end = start + placeholder.length
        val region = regionAt(start)
        if (
            region == SurqlRegion.SINGLE_QUOTE ||
            region == SurqlRegion.DOUBLE_QUOTE ||
            region == SurqlRegion.BACKTICK
        ) {
            throw IllegalArgumentException(
                "Bound placeholder $placeholder is inside quoted SurrealQL text. Interpolate bind(...) without quotes.",
            )
        }
        if (region == SurqlRegion.CODE) {
            val next = getOrNull(end)
            if (next != null && next.isSurqlParameterCharacter()) {
                var tokenEnd = end
                while (getOrNull(tokenEnd)?.isSurqlParameterCharacter() == true) tokenEnd++
                val fullName = substring(start + 1, tokenEnd)
                require(fullName in registeredNames) {
                    "Bound placeholder $placeholder runs into a word character, so SurrealDB reads a different parameter name."
                }
            }
        }
        searchFrom = end
    }
}

private fun Char.isSurqlParameterCharacter(): Boolean =
    this == '_' || this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private enum class SurqlRegion {
    CODE,
    SINGLE_QUOTE,
    DOUBLE_QUOTE,
    BACKTICK,
    LINE_COMMENT,
    BLOCK_COMMENT,
}

private fun String.regionAt(position: Int): SurqlRegion {
    var region = SurqlRegion.CODE
    var index = 0
    while (index < position) {
        val current = this[index]
        val next = getOrNull(index + 1)
        when (region) {
            SurqlRegion.CODE -> {
                when (current) {
                    '\'' -> {
                        region = SurqlRegion.SINGLE_QUOTE
                    }

                    '"' -> {
                        region = SurqlRegion.DOUBLE_QUOTE
                    }

                    '`' -> {
                        region = SurqlRegion.BACKTICK
                    }

                    '-' if next == '-' -> {
                        region = SurqlRegion.LINE_COMMENT
                        index++
                    }

                    '/' if next == '/' -> {
                        region = SurqlRegion.LINE_COMMENT
                        index++
                    }

                    '/' if next == '*' -> {
                        region = SurqlRegion.BLOCK_COMMENT
                        index++
                    }
                }
            }

            SurqlRegion.SINGLE_QUOTE -> {
                when (current) {
                    '\\' -> index++
                    '\'' if next == '\'' -> index++
                    '\'' -> region = SurqlRegion.CODE
                }
            }

            SurqlRegion.DOUBLE_QUOTE -> {
                when (current) {
                    '\\' -> index++
                    '"' if next == '"' -> index++
                    '"' -> region = SurqlRegion.CODE
                }
            }

            SurqlRegion.BACKTICK -> {
                when (current) {
                    '\\' -> index++
                    '`' if next == '`' -> index++
                    '`' -> region = SurqlRegion.CODE
                }
            }

            SurqlRegion.LINE_COMMENT -> {
                if (current == '\n' || current == '\r') region = SurqlRegion.CODE
            }

            SurqlRegion.BLOCK_COMMENT -> {
                if (current == '*' && next == '/') {
                    region = SurqlRegion.CODE
                    index++
                }
            }
        }
        index++
    }
    return region
}
