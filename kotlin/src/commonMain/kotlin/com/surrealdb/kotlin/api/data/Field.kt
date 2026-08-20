package com.surrealdb.kotlin.api.data

/**
 * A typed reference to a field of a [Table], where [path] is the SurrealQL that
 * names it. A dotted path addresses a field of a nested object, and an index
 * addresses one element of an array: `field<String>("address.city")`,
 * `field<String>("tags[0]")`.
 *
 * Field paths are the one thing written into the SurrealQL rather than bound,
 * because SurrealQL has no parameter form for an identifier, so the shape is
 * checked here. Whether the database has the field is a separate question, and
 * [com.surrealdb.kotlin.api.query.checkSchema] is what asks it.
 */
public open class Field<V> internal constructor(
    override val path: String,
) : Projection {
    init {
        require(FIELD_PATH.matches(path)) {
            "Field path must match $FIELD_PATH (got '$path')"
        }
    }

    override fun toString(): String = path

    override fun equals(other: Any?): Boolean = other is Field<*> && other.path == path

    override fun hashCode(): Int = path.hashCode()

    internal companion object {
        val FIELD_PATH =
            Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*|\[(?:[0-9]+|\*)])*""")
    }
}
