package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `UPDATE … MERGE …` queries.
 */
public class MergeQuery internal constructor(
    context: QueryContext,
    private val what: Target,
    private val data: JsonElement,
    private val cond: Expr? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun where(expr: Expr): MergeQuery = copy(cond = expr)

    public fun returnMode(mode: ReturnMode): MergeQuery = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("UPDATE ONLY ")
        q.appendTarget(what)
        q.appendLiteral(" MERGE ")
        q.bind(data)
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
    ): MergeQuery = MergeQuery(context, what, data, cond, returnMode)
}
