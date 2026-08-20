package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Field
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

internal class Assignment(
    val field: Field<*>,
    val op: String,
    val value: Any?,
)

/**
 * What a `set { }` block writes through.
 *
 * It is the block's parameter rather than its receiver, because the receiver is
 * the schema: `set { it[title] = "SICP" }` resolves `title` against the table
 * being written, so a field of some other table has no bare name here. Putting
 * the sink on the receiver instead would mean every [Table] carried mutable
 * statement state.
 */
public class Assignments internal constructor(
    @PublishedApi internal val json: Json,
) {
    internal val entries: MutableList<Assignment> = mutableListOf()

    /**
     * Assign [value] to [field]. The value type comes from the field, so
     * `it[pages] = "657"` fails with "actual type is 'String', but 'Int' was
     * expected".
     *
     * This is deliberately the only overload. A second one taking
     * [com.surrealdb.kotlin.api.data.TableRecord] for link fields would make
     * every mismatch above report against both candidates, which buries the
     * message that matters. Assign a link through its
     * [com.surrealdb.kotlin.api.data.TableRecord.record] instead.
     */
    public inline operator fun <reified V> set(
        field: Field<V>,
        value: V,
    ) {
        record(field, "=", encode(value))
    }

    /**
     * The array field [field] as something `+=` and `-=` can be written
     * against: `it[tags] += "cs"` compiles to `SET tags += $_0`.
     */
    public inline operator fun <reified E> get(field: Field<List<E>>): ArrayField<E> =
        ArrayField(this, field) { element -> encode(element) }

    /**
     * A value the renderer already understands is kept as it is, so a link
     * still renders as `type::record(…)` rather than an object. Everything else
     * is encoded with the context's [Json], which is what lets a composite be
     * assigned without the field carrying a serializer.
     */
    @PublishedApi
    internal inline fun <reified V> encode(value: V): Any? =
        if (value is Target) value else json.encodeToJsonElement(serializer<V>(), value)

    @PublishedApi
    internal fun record(
        field: Field<*>,
        op: String,
        value: Any?,
    ) {
        entries += Assignment(field, op, value)
    }
}

/**
 * One array field inside a `set { }` block. SurrealQL has its own `+=` and `-=`
 * for appending to and removing from an array, and they build the array when
 * the record has no such field yet.
 *
 * The encoder is passed in rather than resolved here, so the element type stays
 * reified at the call site and a list of links keeps working.
 */
public class ArrayField<E>
    @PublishedApi
    internal constructor(
        @PublishedApi internal val sink: Assignments,
        @PublishedApi internal val field: Field<List<E>>,
        @PublishedApi internal val encode: (E) -> Any?,
    ) {
        /** Append [value] to the array. */
        public operator fun plusAssign(value: E) {
            sink.record(field, "+=", encode(value))
        }

        /** Remove [value] from the array. */
        public operator fun minusAssign(value: E) {
            sink.record(field, "-=", encode(value))
        }
    }

/**
 * The one data slot a write statement carries.
 *
 * `CREATE … CONTENT {…} SET …` is a parse error, so the two clauses cannot both
 * be held. A second `content(…)` already replaced the first; `set { }` replaces
 * it on the same terms, and the invalid statement has no way to be built.
 */
internal sealed interface WriteData {
    fun render(into: BoundQuery)
}

internal class ContentData(
    val json: JsonElement,
) : WriteData {
    override fun render(into: BoundQuery) {
        into.appendLiteral(" CONTENT ")
        into.bind(json)
    }
}

internal class SetData(
    val entries: List<Assignment>,
) : WriteData {
    override fun render(into: BoundQuery) {
        into.appendLiteral(" SET ")
        entries.forEachIndexed { index, entry ->
            if (index > 0) into.appendLiteral(", ")
            into.appendLiteral(entry.field.path)
            into.appendLiteral(" ${entry.op} ")
            into.appendValue(entry.value)
        }
    }
}

/**
 * Run a `set { }` block against [schema] and collect what it assigned.
 *
 * A block that assigns nothing yields no clause at all: `SET` with no
 * assignments is a parse error, while a statement with no data clause is a
 * no-op that returns the record untouched.
 */
internal fun <S> buildAssignments(
    json: Json,
    schema: S,
    block: S.(Assignments) -> Unit,
): WriteData? {
    val sink = Assignments(json)
    schema.block(sink)
    return if (sink.entries.isEmpty()) null else SetData(sink.entries.toList())
}
