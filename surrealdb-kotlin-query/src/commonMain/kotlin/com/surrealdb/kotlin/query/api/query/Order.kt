package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.Projection

/**
 * One key of an `ORDER BY` clause: what to sort on, which way round, and how to
 * compare two of them.
 *
 * Build one from the projection with [ascending] or [descending]:
 *
 * ```
 * db.select(People).orderBy(People.name.ascending().collate(), People.age.descending())
 * ```
 */
public class Order internal constructor(
    internal val projection: Projection,
    internal val descending: Boolean,
    internal val collate: Boolean,
    internal val numeric: Boolean,
) {
    init {
        require(!(collate && numeric)) {
            "'${projection.path.value}' cannot be ordered COLLATE and NUMERIC at once: the first compares text " +
                "without regard to case, the second reads the text as a number, and SurrealDB takes one or neither."
        }
    }

    /**
     * Compare as text without regard to case or accent, so `Ada`, `bob`, `Cyd`
     * sort in that order rather than the byte order that puts every capital
     * first.
     */
    public fun collate(): Order = Order(projection, descending, collate = true, numeric = numeric)

    /**
     * Read the text as a number before comparing, so `2` sorts before `10`.
     * This is for a number *stored as a string*; a numeric field already
     * compares numerically.
     */
    public fun numeric(): Order = Order(projection, descending, collate = collate, numeric = true)

    override fun toString(): String =
        buildString {
            append(projection.path.value)
            if (collate) append(" COLLATE")
            if (numeric) append(" NUMERIC")
            append(if (descending) " DESC" else " ASC")
        }
}

/** Sort on this projection, smallest first. */
public fun Projection.ascending(): Order = Order(this, descending = false, collate = false, numeric = false)

/** Sort on this projection, largest first. */
public fun Projection.descending(): Order = Order(this, descending = true, collate = false, numeric = false)
