package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Builder for `CREATE` queries.
 */
public class CreateQuery internal constructor(
    context: QueryContext,
    private val what: Target,
    private val data: JsonElement? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun content(data: JsonElement): CreateQuery = copy(data = data)

    public fun returnMode(mode: ReturnMode): CreateQuery = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("CREATE ONLY ")
        q.appendTarget(what)
        data?.let {
            q.appendLiteral(" CONTENT ")
            q.bind(it)
        }
        returnMode?.render(q)
        return q
    }

    private fun copy(
        data: JsonElement? = this.data,
        returnMode: ReturnMode? = this.returnMode,
    ): CreateQuery = CreateQuery(context, what, data, returnMode)
}
