package com.surrealdb.kotlin.api

import kotlin.jvm.JvmInline

/**
 * The name of a database, for [Session.use] and for the credential shapes that
 * carry one. See [Namespace] for what this pair does and does not prevent.
 */
@JvmInline
public value class Database(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A database name cannot be blank." }
    }

    override fun toString(): String = value
}
