package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `UPSERT` queries.
 */
public class UpsertQuery internal constructor(
    context: QueryContext,
    private val what: Target,
    private val data: JsonElement? = null,
    private val cond: Expr? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun content(data: JsonElement): UpsertQuery = copy(data = data)

    public fun where(expr: Expr): UpsertQuery = copy(cond = expr)

    public fun returnMode(mode: ReturnMode): UpsertQuery = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("UPSERT ONLY ")
        q.appendTarget(what)
        data?.let {
            q.appendLiteral(" CONTENT ")
            q.bind(it)
        }
        cond?.let {
            q.appendLiteral(" WHERE ")
            it.compile(q)
        }
        returnMode?.render(q)
        return q
    }

    private fun copy(
        data: JsonElement? = this.data,
        cond: Expr? = this.cond,
        returnMode: ReturnMode? = this.returnMode,
    ): UpsertQuery = UpsertQuery(context, what, data, cond, returnMode)
}
