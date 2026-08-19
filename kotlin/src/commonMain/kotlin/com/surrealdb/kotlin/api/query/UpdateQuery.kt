package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `UPDATE` queries (replacement-style).
 *
 * For merge / patch behaviour use [MergeQuery] / [PatchQuery] which compile to
 * `UPDATE ... MERGE` / `UPDATE ... PATCH` respectively.
 */
public class UpdateQuery<T, S : Table<T>> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val data: JsonElement? = null,
    private val cond: Condition<T>? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun content(data: JsonElement): UpdateQuery<T, S> = copy(data = data)

    public fun where(build: S.() -> Condition<T>): UpdateQuery<T, S> = copy(cond = schema.build())

    public fun returnMode(mode: ReturnMode): UpdateQuery<T, S> = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("UPDATE ONLY ")
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
    ): UpdateQuery<T, S> = UpdateQuery(context, schema, what, data, cond, returnMode)
}
