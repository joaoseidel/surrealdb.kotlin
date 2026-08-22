package com.surrealdb.kotlin.core.api.data

/** Identifies the path where a projected value appears in a response row. */
public interface Projection {
    /**
     * The SurrealQL that names it, and where its value arrives, so a row reads
     * back through the same declaration that asked for it.
     */
    public val path: FieldPath
}

public interface TypedProjection<out V> : Projection
