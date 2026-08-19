package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `UPDATE … PATCH …` queries (JSON-Patch RFC 6902).
 *
 * When [diff] is true, the result is the diff between before/after states
 * (`RETURN DIFF` is appended).
 */
public class PatchQuery<T, S : Table<T>> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val patches: JsonElement,
    private val diff: Boolean,
    private val cond: Condition<T>? = null,
) : Query(context) {
    public fun where(build: S.() -> Condition<T>): PatchQuery<T, S> =
        PatchQuery(context, schema, what, patches, diff, schema.build())

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
        if (diff) q.appendLiteral(" RETURN DIFF")
        return q
    }
}
