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
public class Field<V> internal constructor(
    path: String,
) : TypedProjection<V> {
    override val path: FieldPath = FieldPath(path)

    override fun toString(): String = path.value

    override fun equals(other: Any?): Boolean = other is Field<*> && other.path == path

    override fun hashCode(): Int = path.hashCode()
}
