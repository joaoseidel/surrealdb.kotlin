package com.surrealdb.kotlin.api

import kotlin.jvm.JvmInline

/**
 * The name of a namespace, for [Session.use] and for the credential shapes that
 * carry one.
 *
 * It exists because a namespace and a database are two adjacent strings, and the
 * server accepts them in either order. `use(Database("app"), Namespace("prod"))`
 * does not compile; `use("app", "prod")` did, and answered `OK`.
 *
 * It does not catch a misspelling, and nothing on the wire does. Signing in as
 * root and selecting a namespace that does not exist **defines** it, along with
 * the database, and answers `OK` with both names echoed back, so every later
 * statement reads and writes an empty database. As a namespace-level user the
 * same call answers `OK` and defines nothing, leaving the session pointed at a
 * namespace it cannot reach, and the next statement fails with
 * `The database 'x' does not exist` about a database that does exist elsewhere.
 * The library does not ask the server to confirm the name, because only root and
 * a namespace-level user may read `INFO FOR ROOT` and `INFO FOR NS`; a
 * database-level or record user is answered `IAM error: Not enough permissions`,
 * so the check would pass by finding nothing exactly where it was cheapest to
 * believe. A blank name is refused here, since it defines a namespace whose name
 * is the empty string.
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
