package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `DELETE` queries.
 */
public class DeleteQuery<T, S : Table<T>> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val cond: Condition<T>? = null,
    private val returnMode: ReturnMode? = null,
    private val only: Boolean? = null,
) : Query(context) {
    public fun where(build: S.() -> Condition<T>): DeleteQuery<T, S> = copy(cond = schema.build())

    public fun returnMode(mode: ReturnMode): DeleteQuery<T, S> = copy(returnMode = mode)

    /**
     * Emit `ONLY`, so the statement answers with the record itself instead of a
     * list of one. A record-id target already does. On a table or range target
     * the server rejects the statement the moment a second record matches.
     */
    public fun only(): DeleteQuery<T, S> = copy(only = true)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("DELETE ")
        q.appendStatementTarget(what, only)
        cond?.let {
            q.appendLiteral(" WHERE ")
            q.appendCondition(it)
        }
        returnMode?.render(q)
        return q
    }

    private fun copy(
        cond: Condition<T>? = this.cond,
        returnMode: ReturnMode? = this.returnMode,
        only: Boolean? = this.only,
    ): DeleteQuery<T, S> = DeleteQuery(context, schema, what, cond, returnMode, only)
}
