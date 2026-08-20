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
public class UpdateQuery<S : Table> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val data: WriteData? = null,
    private val cond: Condition? = null,
    private val returnMode: ReturnMode? = null,
    private val forceOnly: Boolean = false,
) : Query(context) {
    public fun content(data: JsonElement): UpdateQuery<S> = copy(data = ContentData(data))

    /** Assign fields by name: `set { it[pages] = 0 }`. Replaces any [content]. */
    public fun set(block: S.(Assignments) -> Unit): UpdateQuery<S> =
        copy(data = buildAssignments(context.json, schema, block))

    public fun where(build: S.() -> Condition): UpdateQuery<S> = copy(cond = schema.build())

    public fun returnMode(mode: ReturnMode): UpdateQuery<S> = copy(returnMode = mode)

    /**
     * Emit `ONLY`, so the statement answers with the record itself instead of a
     * list of one. A record-id target already does. On a table or range target
     * the server rejects the statement the moment a second record matches.
     */
    public fun only(): UpdateQuery<S> = copy(forceOnly = true)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("UPDATE ")
        q.appendStatementTarget(what, forceOnly)
        data?.render(q)
        cond?.let {
            q.appendLiteral(" WHERE ")
            q.appendCondition(it)
        }
        returnMode?.render(q)
        return q
    }

    private fun copy(
        data: WriteData? = this.data,
        cond: Condition? = this.cond,
        returnMode: ReturnMode? = this.returnMode,
        forceOnly: Boolean = this.forceOnly,
    ): UpdateQuery<S> = UpdateQuery(context, schema, what, data, cond, returnMode, forceOnly)
}
