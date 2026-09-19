package com.surrealdb.kotlin.core.api

import kotlin.jvm.JvmInline

/**
 * The name of a namespace, for [Session.use] and for the credential shapes that
 * carry one.
 *
 * A misspelling is not refused, here or on the wire. As root, [Session.use] on a
 * namespace that does not exist **defines** it along with the database and
 * answers `OK`, so every later statement reads and writes an empty database. At
 * every other level the same call answers `OK` and selects the pair anyway; the
 * next statement then fails, with `The database 'x' does not exist` for a
 * system user and `You don't have permission to change to the x namespace` for
 * a record user. Selecting a namespace on its own leaves no database selected,
 * since a database belongs to a namespace.
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
