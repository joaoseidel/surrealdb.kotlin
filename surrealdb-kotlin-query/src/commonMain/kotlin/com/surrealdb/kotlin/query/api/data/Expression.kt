package com.surrealdb.kotlin.query.api.data

/**
 * Something that names a value inside a statement.
 *
 * A [Field] names one the record carries. A [Walk] names one reached by
 * following edges out of it. Both stand in the same places, so the operators on
 * [Table] take either and a condition reads the same whichever it compares.
 *
 * The variants are the library's own: a statement knows how to render these two
 * and nothing else.
 */
public sealed interface Expression<V>
