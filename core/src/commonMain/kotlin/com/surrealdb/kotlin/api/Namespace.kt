package com.surrealdb.kotlin.api

import kotlin.jvm.JvmInline

/**
 * The name of a namespace, for [Session.use] and for the credential shapes that
 * carry one.
 *
 * A misspelling is not refused, here or on the wire. As root, [Session.use] on a
 * namespace that does not exist **defines** it along with the database and
 * answers `OK`, so every later statement reads and writes an empty database. At
 * every other level the same call answers `OK`, selects nothing, and the next
 * statement fails with `The database 'x' does not exist`.
 */
@JvmInline
public value class Namespace(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A namespace name cannot be blank." }
    }

    override fun toString(): String = value
}
