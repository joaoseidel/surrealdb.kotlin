package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `UPDATE … MERGE …` queries.
 */
public class MergeQuery<T, S : Table<T>> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val data: JsonElement,
    private val cond: Condition<T>? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun where(build: S.() -> Condition<T>): MergeQuery<T, S> = copy(cond = schema.build())

    public fun returnMode(mode: ReturnMode): MergeQuery<T, S> = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("UPDATE ONLY ")
        q.appendTarget(what)
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
        cond: Condition<T>? = this.cond,
        returnMode: ReturnMode? = this.returnMode,
    ): MergeQuery<T, S> = MergeQuery(context, schema, what, data, cond, returnMode)
}
