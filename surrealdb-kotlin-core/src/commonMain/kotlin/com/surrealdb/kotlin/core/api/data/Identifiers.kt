package com.surrealdb.kotlin.core.api.data

/**
 * Quote [value] unless SurrealDB would read it back bare.
 *
 * The rule is SurrealDB's own: an identifier may hold letters, digits and
 * underscores and is written as it stands, except that one reading as a number
 * has to be quoted. `person:1` is the record whose key is the integer 1, which
 * is a different record from the one whose key is the string "1".
 */
internal fun escapeIdent(value: String): String =
    if (value.isNotEmpty() && value.all { it.isBareIdentChar() } && !READS_AS_A_NUMBER.matches(value)) {
        value
    } else {
        "`" + value.replace("\\", "\\\\").replace("`", "\\`") + "`"
    }

private fun Char.isBareIdentChar(): Boolean = this == '_' || this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private val READS_AS_A_NUMBER = Regex("""\d+([eE]\d+)?(f|dec)?|NaN""")
