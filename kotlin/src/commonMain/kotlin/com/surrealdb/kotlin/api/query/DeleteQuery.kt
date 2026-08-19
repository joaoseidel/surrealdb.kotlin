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
) : Query(context) {
    public fun where(build: S.() -> Condition<T>): DeleteQuery<T, S> = copy(cond = schema.build())

    public fun returnMode(mode: ReturnMode): DeleteQuery<T, S> = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("DELETE ONLY ")
        q.appendTarget(what)
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
    ): DeleteQuery<T, S> = DeleteQuery(context, schema, what, cond, returnMode)
}
