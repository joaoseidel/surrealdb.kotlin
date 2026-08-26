package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.Target
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Condition
import com.surrealdb.kotlin.query.api.data.Field
import com.surrealdb.kotlin.query.api.data.Table

/**
 * How many records a target holds, under the same `where { }` a
 * [SelectQuery] takes.
 *
 * ```
 * val adults = db.count(People).where { age greaterEq 18 }.await()
 * ```
 *
 * It compiles to `SELECT count() FROM … GROUP ALL`, which answers with one row
 * holding the number. `GROUP ALL` is what makes that one row: without it
 * SurrealDB answers with a row per record, each counting itself.
 *
 * Counting is a question about a set, so unlike the other builders this one
 * never emits `ONLY`, and a target that matched nothing answers zero rather
 * than nothing.
 */
public class CountQuery<S : Table> internal constructor(
    private val context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val cond: Condition? = null,
) {
    public fun where(build: S.() -> Condition): CountQuery<S> = CountQuery(context, schema, what, schema.build())

    /**
     * Narrow by [condition], or by nothing at all when it is null.
     *
     * A filter assembled from optional parts has no condition to give when the
     * caller set none, and `WHERE` with nothing after it does not parse. This
     * takes the decision, so the caller does not have to branch around the
     * builder.
     */
    public fun where(condition: Condition?): CountQuery<S> = CountQuery(context, schema, what, condition)

    /** Compile this query without dispatching it. */
    public fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("SELECT count() FROM ")
        q.appendTarget(what)
        cond?.let {
            q.appendLiteral(" WHERE ")
            q.appendCondition(it)
        }
        q.appendLiteral(" GROUP ALL")
        return q
    }

    /** Send the statement and read the number back. */
    public suspend fun await(): Long = context.queryValues(compile()).firstOrNull()?.get(COUNT) ?: 0L

    private companion object {
        /** The key `count()` answers on, which the statement does not get to name. */
        val COUNT: Field<Long> = Field("count")
    }
}

/** How many records of [table] there are. See [CountQuery]. */
public fun <S : Table> QueryContext.count(table: S): CountQuery<S> = CountQuery(this, table, table)

/**
 * How many records [what] reaches — a table, a
 * [com.surrealdb.kotlin.query.api.data.Walk], or any other target. See
 * [CountQuery].
 */
public fun QueryContext.count(what: Target): CountQuery<Table> = CountQuery(this, schemaOf(what), what)
