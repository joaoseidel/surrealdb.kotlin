package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `CREATE` queries.
 */
public class CreateQuery<T, S : Table<T>> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val data: JsonElement? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun content(data: JsonElement): CreateQuery<T, S> = copy(data = data)

    public fun returnMode(mode: ReturnMode): CreateQuery<T, S> = copy(returnMode = mode)

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
    ): CreateQuery<T, S> = CreateQuery(context, schema, what, data, returnMode)
}
