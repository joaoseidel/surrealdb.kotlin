package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `UPSERT` queries.
 */
public class UpsertQuery<T, S : Table<T>> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val data: JsonElement? = null,
    private val cond: Condition<T>? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun content(data: JsonElement): UpsertQuery<T, S> = copy(data = data)

    public fun where(build: S.() -> Condition<T>): UpsertQuery<T, S> = copy(cond = schema.build())

    public fun returnMode(mode: ReturnMode): UpsertQuery<T, S> = copy(returnMode = mode)

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
            q.appendCondition(it)
        }
        returnMode?.render(q)
        return q
    }

    private fun copy(
        data: JsonElement? = this.data,
        cond: Condition<T>? = this.cond,
        returnMode: ReturnMode? = this.returnMode,
    ): UpsertQuery<T, S> = UpsertQuery(context, schema, what, data, cond, returnMode)
}
