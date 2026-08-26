package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.RecordIdRange
import com.surrealdb.kotlin.core.api.data.Target
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.data.TableRecord
import com.surrealdb.kotlin.query.api.data.escapeIdent
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `RELATE` queries.
 */
public class RelateQuery<S : Table> internal constructor(
    context: QueryContext,
    private val schema: S,
    private val `in`: Target,
    private val relation: Target,
    private val out: Target,
    private val data: JsonElement? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    /**
     * Give the edge record exactly the fields [build] names, resolved against
     * the relation's own declaration:
     *
     * ```
     * db.relate(ada, Participates, chat).content { it[role] = "owner" }
     * ```
     */
    public fun content(build: S.(ContentPayload) -> Unit): RelateQuery<S> =
        copy(data = buildContentPayload(context.json, schema, build))

    /** The same, for a payload assembled elsewhere. */
    public fun content(data: JsonElement): RelateQuery<S> = copy(data = data)

    public fun returnMode(mode: ReturnMode): RelateQuery<S> = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        // RELATE positions don't accept bare `type::record(...)` function
        // calls, but they do accept parenthesised expressions. The relation
        // slot must be either a literal table identifier (e.g. `likes`) or a
        // specific relation record id; same constraint as RECORD references.
        val q = BoundQuery()
        q.appendLiteral("RELATE ")
        renderRelateOperand(q, `in`)
        q.appendLiteral("->")
        renderRelationSlot(q, relation)
        q.appendLiteral("->")
        renderRelateOperand(q, out)
        data?.let {
            q.appendLiteral(" CONTENT ")
            q.bind(it)
        }
        returnMode?.render(q)
        return q
    }

    private fun renderRelateOperand(
        q: BoundQuery,
        operand: Target,
    ) {
        when (operand) {
            is RecordId -> {
                q.appendLiteral("(")
                q.appendTarget(operand)
                q.appendLiteral(")")
            }

            is TableRecord<*> -> {
                renderRelateOperand(q, operand.record)
            }

            is Table -> {
                q.appendTarget(operand)
            }

            is RecordIdRange -> {
                rejectRange("RELATE operand", operand)
            }
        }
    }

    private fun renderRelationSlot(
        q: BoundQuery,
        slot: Target,
    ) {
        when (slot) {
            is Table -> {
                q.appendLiteral(escapeIdent(slot.tableName))
            }

            is RecordId -> {
                renderRelateOperand(q, slot)
            }

            is TableRecord<*> -> {
                renderRelateOperand(q, slot.record)
            }

            is RecordIdRange -> {
                rejectRange("RELATE relation", slot)
            }
        }
    }

    /**
     * A range does not parse in either RELATE position; the server answers
     * `Unexpected token '..', expected a relation arrow`.
     */
    private fun rejectRange(
        position: String,
        range: RecordIdRange,
    ): Nothing =
        throw IllegalArgumentException(
            "Record-id range '$range' cannot be a $position; SurrealQL does not parse a range there.",
        )

    private fun copy(
        data: JsonElement? = this.data,
        returnMode: ReturnMode? = this.returnMode,
    ): RelateQuery<S> = RelateQuery(context, schema, `in`, relation, out, data, returnMode)
}
