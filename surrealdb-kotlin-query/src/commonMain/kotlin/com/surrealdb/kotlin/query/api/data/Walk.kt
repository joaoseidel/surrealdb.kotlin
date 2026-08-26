package com.surrealdb.kotlin.query.api.data

import com.surrealdb.kotlin.core.api.data.FieldPath
import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.Target
import com.surrealdb.kotlin.core.api.data.TypedProjection

/**
 * A graph traversal: the records reached by following edges out of, or into,
 * the record a statement is reading.
 *
 * ```
 * // (book:hobbit)->has_chapter->book_chapter, as a statement target
 * db.select(RecordId("book", "hobbit").outgoing(HasChapter, Chapters))
 *
 * // <-authored<-user, from the record being read
 * db.select(Books).where { incoming(Authored, Users) contains reader }
 * ```
 *
 * A walk is a [Target], so a statement can point at one, and an [Expression],
 * so a condition can compare against one. It is not a projection until it is
 * given a name with [aliasedAs], because SurrealDB answers `SELECT
 * <-authored<-user` on a key spelled like the traversal, which no field
 * declaration can name.
 *
 * The destination table is part of every step and is not decoration: SurrealDB
 * keeps only the edges whose far end is in that table, which is what stops a
 * `follows` edge pointing at a book from arriving where users were asked for.
 */
public class Walk<V> internal constructor(
    internal val start: Target?,
    internal val steps: List<WalkStep>,
    internal val destination: Table,
) : Target,
    Expression<V> {
    override fun toString(): String = buildString { render({ append(it) }, { append(it) }) }
}

internal class WalkStep(
    val outgoing: Boolean,
    val edge: String,
    val destination: String,
    val index: Int?,
)

/**
 * Write this walk out, one fragment at a time.
 *
 * The two sinks are passed in rather than resolved here because the statement
 * compiler and the condition compiler each bind values their own way, and a
 * walk that started at a record has one to bind.
 */
internal fun Walk<*>.render(
    literal: (String) -> Unit,
    target: (Target) -> Unit,
) {
    start?.let {
        literal("(")
        target(it)
        literal(")")
    }

    steps.forEach { step ->
        val arrow = if (step.outgoing) "->" else "<-"
        literal(arrow + escapeIdent(step.edge) + arrow + escapeIdent(step.destination))
        step.index?.let { literal("[$it]") }
    }
}

/**
 * `->edge->to`, from the record the statement is reading. Use it inside a
 * `where { }` or as a projection; a walk from a *named* record is the overload
 * taking one as its receiver.
 */
public fun outgoing(
    edge: Table,
    to: Table,
): Walk<List<RecordId>> = Walk(null, listOf(WalkStep(outgoing = true, edge.tableName, to.tableName, null)), to)

/** `<-edge<-from`, from the record the statement is reading. See [outgoing]. */
public fun incoming(
    edge: Table,
    from: Table,
): Walk<List<RecordId>> = Walk(null, listOf(WalkStep(outgoing = false, edge.tableName, from.tableName, null)), from)

/**
 * `(this)->edge->to`.
 *
 * The receiver is a record rather than any [Target] because SurrealDB walks
 * from a record: `book->has_chapter->chapter` over a whole table answers with
 * nothing at all rather than refusing, so a table start is a query that
 * silently finds nothing. Point a statement at the table and walk from the
 * record it is reading instead.
 */
public fun RecordId.outgoing(
    edge: Table,
    to: Table,
): Walk<List<RecordId>> = Walk(this, listOf(WalkStep(outgoing = true, edge.tableName, to.tableName, null)), to)

/** `(this)<-edge<-from`. See [RecordId.outgoing]. */
public fun RecordId.incoming(
    edge: Table,
    from: Table,
): Walk<List<RecordId>> = Walk(this, listOf(WalkStep(outgoing = false, edge.tableName, from.tableName, null)), from)

/** `(this)->edge->to`. See [RecordId.outgoing]. */
public fun TableRecord<*>.outgoing(
    edge: Table,
    to: Table,
): Walk<List<RecordId>> = record.outgoing(edge, to)

/** `(this)<-edge<-from`. See [RecordId.outgoing]. */
public fun TableRecord<*>.incoming(
    edge: Table,
    from: Table,
): Walk<List<RecordId>> = record.incoming(edge, from)

/** Continue this walk with `->edge->to`. */
public fun Walk<*>.outgoing(
    edge: Table,
    to: Table,
): Walk<List<RecordId>> = Walk(start, steps + WalkStep(outgoing = true, edge.tableName, to.tableName, null), to)

/** Continue this walk with `<-edge<-from`. */
public fun Walk<*>.incoming(
    edge: Table,
    from: Table,
): Walk<List<RecordId>> = Walk(start, steps + WalkStep(outgoing = false, edge.tableName, from.tableName, null), from)

/**
 * One record of what the last step reached, `…->author[0]`.
 *
 * The type narrows with the SurrealQL: a walk answers with a list, and without
 * the index a projection declared to hold one record receives a list of one.
 */
public fun <E> Walk<List<E>>.at(index: Int): Walk<E> {
    require(index >= 0) { "A walk index is a position in what the step reached, so it cannot be negative: $index." }
    require(steps.isNotEmpty()) { "A walk has to have a step to index into." }
    require(steps.last().index == null) { "This walk is already indexed at [${steps.last().index}]." }

    val last = steps.last()
    return Walk(start, steps.dropLast(1) + WalkStep(last.outgoing, last.edge, last.destination, index), destination)
}

/** The first record the last step reached, `…->author[0]`. See [at]. */
public fun <E> Walk<List<E>>.first(): Walk<E> = at(0)

/**
 * Name this walk so a statement can project it and a row can read it back:
 * `<-authored<-user[0] AS author`.
 *
 * The alias is a declared [Field], so the value arrives where that field says
 * it does and `row[Books.author]` reads it. Its type has to agree with the
 * walk's, which is what makes an unindexed walk assigned to a single-record
 * field a compile error rather than a decode failure.
 */
public infix fun <V> Walk<V>.aliasedAs(alias: Field<V>): AliasedWalk<V> = AliasedWalk(this, alias)

/**
 * A [Walk] projected under the name of a declared field.
 *
 * Its [path] is the alias, not the traversal, because the alias is where the
 * value arrives and a row reads through the same declaration that asked for it.
 */
public class AliasedWalk<V> internal constructor(
    internal val walk: Walk<V>,
    internal val alias: Field<V>,
) : TypedProjection<V> {
    override val path: FieldPath get() = alias.path

    override fun toString(): String = "$walk AS ${alias.path.value}"
}
