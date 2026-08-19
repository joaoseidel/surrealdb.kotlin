package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.RecordIdRange
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.JsonElement

/**
 * Builder for `RELATE` queries.
 */
private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

public class RelateQuery internal constructor(
    context: QueryContext,
    private val `in`: Target,
    private val relation: Target,
    private val out: Target,
    private val data: JsonElement? = null,
    private val returnMode: ReturnMode? = null,
) : Query(context) {
    public fun content(data: JsonElement): RelateQuery = copy(data = data)

    public fun returnMode(mode: ReturnMode): RelateQuery = copy(returnMode = mode)

    override fun compile(): BoundQuery {
        // RELATE positions don't accept bare `type::record(...)` function
        // calls, but they do accept parenthesised expressions. The relation
        // slot must be either a literal table identifier (e.g. `likes`) or a
        // specific relation record id — same constraint as RECORD references.
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

            is Table<*> -> {
                q.appendTarget(operand)
            }

            is RecordIdRange -> {
                rejectRange("RELATE operand")
            }
        }
    }

    private fun renderRelationSlot(
        q: BoundQuery,
        slot: Target,
    ) {
        when (slot) {
            is Table<*> -> {
                require(IDENTIFIER.matches(slot.tableName)) {
                    "Relation table must be an identifier (got '${slot.tableName}')"
                }
                q.appendLiteral(slot.tableName)
            }

            is RecordId -> {
                renderRelateOperand(q, slot)
            }

            is RecordIdRange -> {
                rejectRange("RELATE relation")
            }
        }
    }

    /**
     * A range does not parse in either RELATE position — the server answers
     * `Unexpected token '..', expected a relation arrow`.
     */
    private fun rejectRange(position: String): Nothing =
        throw IllegalArgumentException(
            "A RecordIdRange cannot be a $position — SurrealQL does not parse a range there.",
        )

    private fun copy(
        data: JsonElement? = this.data,
        returnMode: ReturnMode? = this.returnMode,
    ): RelateQuery = RelateQuery(context, `in`, relation, out, data, returnMode)
}
