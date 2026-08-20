package com.surrealdb.kotlin.api.data

/**
 * Something a statement can name in its projection: a [Field], or a [Nested]
 * group standing for the whole object it describes.
 *
 * ```
 * db.select(Users).fields(Users.name, Users.address)
 * ```
 *
 * [path] is the SurrealQL naming it, and it is also where the value arrives, so
 * a row reads back through the same declaration that asked for it.
 */
public sealed interface Projection {
    /** The SurrealQL that names it. */
    public val path: String
}
