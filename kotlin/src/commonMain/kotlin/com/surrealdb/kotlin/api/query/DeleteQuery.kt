package com.surrealdb.kotlin.api.query

import kotlinx.serialization.json.JsonElement

/**
 * Builder for `DELETE` queries.
 */
public class DeleteQuery internal constructor(
    context: QueryContext,
    private val what: Any,
    private val cond: Expr? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun where(expr: Expr): DeleteQuery = copy(cond = expr)

    public fun returnMode(mode: ReturnMode): DeleteQuery = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("DELETE ONLY ")
        q.appendValue(what)
        cond?.let {
            q.appendLiteral(" WHERE ")
            it.compile(q)
        }
        returnMode?.render(q)
        return q
    }

    private fun copy(
        cond: Expr? = this.cond,
        returnMode: ReturnMode? = this.returnMode,
    ): DeleteQuery = DeleteQuery(context, what, cond, returnMode)
}
