package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Field
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builder for `SELECT` queries. Ports the surface of surrealdb.js's
 * [`SelectPromise`](https://github.com/surrealdb/surrealdb.js/blob/ca8dae20ba439b6b4242ff2822b271a2e5aaaa60/packages/sdk/src/query/select.ts).
 *
 * Each chain method returns a fresh instance so builders are safe to share or
 * pin to a variable.
 */
public class SelectQuery<T, S : Table<T>> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val selection: Selection = Selection.All,
    private val fields: List<Field<*>> = emptyList(),
    private val start: Int? = null,
    private val limit: Int? = null,
    private val cond: Condition<T>? = null,
    private val fetchFields: List<Field<*>> = emptyList(),
    private val timeoutSeconds: Double? = null,
    private val versionAt: String? = null,
) : Query(context) {
    internal enum class Selection { All, Fields, Value }

    /** Select only the named fields. */
    public fun fields(vararg fields: Field<*>): SelectQuery<T, S> =
        copy(selection = Selection.Fields, fields = fields.toList())

    /** Project a single field as VALUE. */
    public fun value(field: Field<*>): SelectQuery<T, S> = copy(selection = Selection.Value, fields = listOf(field))

    public fun start(start: Int): SelectQuery<T, S> = copy(start = start)

    public fun limit(limit: Int): SelectQuery<T, S> = copy(limit = limit)

    public fun where(build: S.() -> Condition<T>): SelectQuery<T, S> = copy(cond = schema.build())

    public fun fetch(vararg fields: Field<*>): SelectQuery<T, S> = copy(fetchFields = fields.toList())

    public fun timeout(seconds: Double): SelectQuery<T, S> = copy(timeoutSeconds = seconds)

    /** Version cutoff as a SurrealQL datetime literal (e.g. `d'2024-01-01T00:00:00Z'`). */
    public fun version(literal: String): SelectQuery<T, S> = copy(versionAt = literal)

    /** Compile this query without dispatching it. */
    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("SELECT")
        when (selection) {
            Selection.All -> q.appendLiteral(" *")
            Selection.Fields -> q.appendLiteral(" " + fields.joinToString(", ") { it.path })
            Selection.Value -> q.appendLiteral(" VALUE " + fields.first().path)
        }
        q.appendLiteral(" FROM ONLY ")
        q.appendTarget(what)
        cond?.let {
            q.appendLiteral(" WHERE ")
            q.appendCondition(it)
        }
        start?.let {
            q.appendLiteral(" START ")
            q.bind(JsonPrimitive(it))
        }
        limit?.let {
            q.appendLiteral(" LIMIT ")
            q.bind(JsonPrimitive(it))
        }
        if (fetchFields.isNotEmpty()) q.appendLiteral(" FETCH " + fetchFields.joinToString(", ") { it.path })
        timeoutSeconds?.let { q.appendLiteral(" TIMEOUT ${it}s") }
        versionAt?.let { q.appendLiteral(" VERSION $it") }
        return q
    }

    private fun copy(
        selection: Selection = this.selection,
        fields: List<Field<*>> = this.fields,
        start: Int? = this.start,
        limit: Int? = this.limit,
        cond: Condition<T>? = this.cond,
        fetchFields: List<Field<*>> = this.fetchFields,
        timeoutSeconds: Double? = this.timeoutSeconds,
        versionAt: String? = this.versionAt,
    ): SelectQuery<T, S> =
        SelectQuery(
            context,
            schema,
            what,
            selection,
            fields,
            start,
            limit,
            cond,
            fetchFields,
            timeoutSeconds,
            versionAt,
        )
}
