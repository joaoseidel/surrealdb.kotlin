package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target

/**
 * Builder for `DELETE` queries.
 */
public class DeleteQuery<S : Table> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val cond: Condition? = null,
    private val returnMode: ReturnMode? = null,
    private val forceOnly: Boolean = false,
) : Query(context) {
    public fun where(build: S.() -> Condition): DeleteQuery<S> = copy(cond = schema.build())

    public fun returnMode(mode: ReturnMode): DeleteQuery<S> = copy(returnMode = mode)

    /**
     * Emit `ONLY`, so the statement answers with the record itself instead of a
     * list of one. A record-id target already does. On a table or range target
     * the server rejects the statement the moment a second record matches.
     */
    public fun only(): DeleteQuery<S> = copy(forceOnly = true)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("DELETE ")
        q.appendStatementTarget(what, forceOnly)
        cond?.let {
            q.appendLiteral(" WHERE ")
            q.appendCondition(it)
        }
        returnMode?.render(q)
        return q
    }

    private fun copy(
        cond: Condition? = this.cond,
        returnMode: ReturnMode? = this.returnMode,
        forceOnly: Boolean = this.forceOnly,
    ): DeleteQuery<S> = DeleteQuery(context, schema, what, cond, returnMode, forceOnly)
}
