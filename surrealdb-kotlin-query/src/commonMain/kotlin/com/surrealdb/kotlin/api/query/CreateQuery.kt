package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `CREATE` queries.
 */
public class CreateQuery<S : Table> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val data: WriteData? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    /**
     * Create the record with exactly the fields [data] names, and no others.
     *
     * The document arrives as a [JsonElement] because this is the escape for a
     * payload assembled elsewhere. [set] is the route that names fields.
     */
    public fun content(data: JsonElement): CreateQuery<S> = copy(data = ContentData(data))

    /** Assign fields by name: `set { it[title] = "SICP" }`. Replaces any [content]. */
    public fun set(block: S.(Assignments) -> Unit): CreateQuery<S> =
        copy(data = buildAssignments(context.json, schema, block))

    public fun returnMode(mode: ReturnMode): CreateQuery<S> = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("CREATE ")
        q.appendStatementTarget(what, forceOnly = true)
        data?.render(q)
        returnMode?.render(q)
        return q
    }

    private fun copy(
        data: WriteData? = this.data,
        returnMode: ReturnMode? = this.returnMode,
    ): CreateQuery<S> = CreateQuery(context, schema, what, data, returnMode)
}
