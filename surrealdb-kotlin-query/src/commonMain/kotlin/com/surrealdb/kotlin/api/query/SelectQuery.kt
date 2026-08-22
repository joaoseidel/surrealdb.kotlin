package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Condition
import com.surrealdb.kotlin.api.data.Field
import com.surrealdb.kotlin.api.data.Projection
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonPrimitive

public class SelectQuery<S : Table> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val what: Target,
    private val selection: Selection = Selection.All,
    private val fields: List<Projection> = emptyList(),
    private val start: Int? = null,
    private val limit: Int? = null,
    private val cond: Condition? = null,
    private val fetchFields: List<Field<*>> = emptyList(),
    private val timeoutSeconds: Double? = null,
    private val versionAt: String? = null,
    private val forceOnly: Boolean = false,
) : Query(context) {
    internal enum class Selection { All, Fields, Value }

    /**
     * Select only what is named here. A [com.surrealdb.kotlin.api.data.Nested]
     * group stands for the whole object it describes, and every leaf under it
     * still reads through its own declaration.
     */
    public fun fields(vararg projections: Projection): SelectQuery<S> =
        copy(selection = Selection.Fields, fields = projections.toList())

    /**
     * Project a single field as `SELECT VALUE`, so the statement answers with
     * the values themselves rather than with records. [Query.await] reads
     * records and rejects those, so read them with
     * `decodeAs<V>().await()`.
     */
    public fun value(projection: Projection): SelectQuery<S> =
        copy(selection = Selection.Value, fields = listOf(projection))

    public fun start(start: Int): SelectQuery<S> = copy(start = start)

    public fun limit(limit: Int): SelectQuery<S> = copy(limit = limit)

    public fun where(build: S.() -> Condition): SelectQuery<S> = copy(cond = schema.build())

    public fun fetch(vararg fields: Field<*>): SelectQuery<S> = copy(fetchFields = fields.toList())

    public fun timeout(seconds: Double): SelectQuery<S> = copy(timeoutSeconds = seconds)

    /** Version cutoff as a SurrealQL datetime literal (e.g. `d'2024-01-01T00:00:00Z'`). */
    public fun version(literal: String): SelectQuery<S> = copy(versionAt = literal)

    /**
     * Emit `ONLY`, so the statement answers with the record itself instead of a
     * list of one. A record-id target already does. On a table target pair this
     * with [limit] (1), because the server rejects the statement the moment a
     * second row matches; `only()` adds no limit of its own.
     */
    public fun only(): SelectQuery<S> = copy(forceOnly = true)

    /** Compile this query without dispatching it. */
    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral("SELECT")
        when (selection) {
            Selection.All -> {
                q.appendLiteral(" *")
            }

            Selection.Fields -> {
                q.appendLiteral(" ")
                q.appendProjections(fields)
            }

            Selection.Value -> {
                q.appendLiteral(" VALUE " + fields.first().path.value)
            }
        }
        q.appendLiteral(" FROM ")
        q.appendStatementTarget(what, forceOnly)
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
        if (fetchFields.isNotEmpty()) {
            q.appendLiteral(" FETCH " + fetchFields.joinToString(", ") { it.path.value })
        }
        timeoutSeconds?.let { q.appendLiteral(" TIMEOUT ${it}s") }
        versionAt?.let { q.appendLiteral(" VERSION $it") }
        return q
    }

    private fun copy(
        selection: Selection = this.selection,
        fields: List<Projection> = this.fields,
        start: Int? = this.start,
        limit: Int? = this.limit,
        cond: Condition? = this.cond,
        fetchFields: List<Field<*>> = this.fetchFields,
        timeoutSeconds: Double? = this.timeoutSeconds,
        versionAt: String? = this.versionAt,
        forceOnly: Boolean = this.forceOnly,
    ): SelectQuery<S> =
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
            forceOnly,
        )
}
