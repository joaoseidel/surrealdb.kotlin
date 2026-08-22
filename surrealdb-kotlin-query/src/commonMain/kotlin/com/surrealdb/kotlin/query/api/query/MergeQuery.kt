package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.Target
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Condition
import com.surrealdb.kotlin.query.api.data.Table
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `UPDATE … MERGE …` queries: the fields the payload names are
 * written and the rest of the record is left alone.
 *
 * The payload is built by a `merge { }` block over the table's fields; see
 * [MergePayload].
 */
public class MergeQuery<S : Table> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val data: JsonElement,
    private val cond: Condition? = null,
    private val returnMode: ReturnMode? = null,
    private val forceOnly: Boolean = false,
) : Query(context) {
    public fun where(build: S.() -> Condition): MergeQuery<S> = copy(cond = schema.build())

    public fun returnMode(mode: ReturnMode): MergeQuery<S> = copy(returnMode = mode)

    /**
     * Emit `ONLY`, so the statement answers with the record itself instead of a
     * list of one. A record-id target already does. On a table or range target
     * the server rejects the statement the moment a second record matches.
     */
    public fun only(): MergeQuery<S> = copy(forceOnly = true)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("UPDATE ")
        q.appendStatementTarget(what, forceOnly)
        q.appendLiteral(" MERGE ")
        q.bind(data)
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
    ): MergeQuery<S> = MergeQuery(context, schema, what, data, cond, returnMode, forceOnly)
}
