package com.surrealdb.kotlin.api.data

internal fun escapeIdent(value: String): String =
    if (BARE_IDENT.matches(value)) {
        value
    } else {
        "`" + value.replace("\\", "\\\\").replace("`", "\\`") + "`"
    }

private val BARE_IDENT = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
