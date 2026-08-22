package com.surrealdb.kotlin.core.api.live

internal fun toLiveStatement(spec: String): String {
    val trimmed = spec.trim().trimEnd(';').trimEnd()
    require(trimmed.isNotEmpty()) { "A live query needs a table or a SELECT statement, not a blank spec" }

    return when {
        trimmed.startsWith("LIVE ", ignoreCase = true) -> trimmed

        trimmed.startsWith("SELECT ", ignoreCase = true) -> "LIVE $trimmed"

        trimmed.none { it.isWhitespace() } -> "LIVE SELECT * FROM $trimmed"

        else -> throw IllegalArgumentException(
            "Cannot read '$spec' as a live query. Name a table (\"book\"), write a SELECT " +
                "statement (\"SELECT * FROM book WHERE pages > 5\"), or give a complete LIVE SELECT.",
        )
    }
}
