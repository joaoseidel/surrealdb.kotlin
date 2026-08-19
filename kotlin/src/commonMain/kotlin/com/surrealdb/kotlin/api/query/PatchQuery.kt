package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `UPDATE … PATCH …` queries (JSON-Patch RFC 6902).
 */
public class PatchQuery<T, S : Table<T>> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val patches: JsonElement,
    private val cond: Condition<T>? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun where(build: S.() -> Condition<T>): PatchQuery<T, S> = copy(cond = schema.build())

    public fun returnMode(mode: ReturnMode): PatchQuery<T, S> = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("UPDATE ONLY ")
        q.appendTarget(what)
        q.appendLiteral(" PATCH ")
        q.bind(patches)
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
    ): PatchQuery<T, S> = PatchQuery(context, schema, what, patches, cond, returnMode)
}
