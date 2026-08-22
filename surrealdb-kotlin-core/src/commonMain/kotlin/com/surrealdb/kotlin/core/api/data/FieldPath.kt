package com.surrealdb.kotlin.core.api.data

import kotlin.jvm.JvmInline

@JvmInline
public value class FieldPath(
    public val value: String,
) {
    init {
        require(PATTERN.matches(value)) {
            "Field path '$value' must match $PATTERN."
        }
    }

    override fun toString(): String = value

    internal companion object {
        val PATTERN = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*|\[(?:[0-9]+|\*)])*""")
    }
}
