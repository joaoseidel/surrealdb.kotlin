package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.Target
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Condition
import com.surrealdb.kotlin.query.api.data.Table
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
    /**
     * Write [data] as the whole record: every field it does not name is gone.
     * A merge leaves the rest alone; this does not.
     *
     * The document arrives as a [JsonElement] because this is the escape for a
     * payload assembled elsewhere. [set] is the route that names fields.
     */
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
