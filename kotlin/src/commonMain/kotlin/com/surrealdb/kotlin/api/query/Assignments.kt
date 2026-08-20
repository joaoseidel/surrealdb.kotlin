package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Field
import com.surrealdb.kotlin.api.data.Table
import kotlinx.serialization.json.JsonElement

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
public class Assignments internal constructor() {
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
    public operator fun <V> set(
        field: Field<V>,
        value: V,
    ) {
        entries += Assignment(field, "=", value)
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
    schema: S,
    block: S.(Assignments) -> Unit,
): WriteData? {
    val sink = Assignments()
    schema.block(sink)
    return if (sink.entries.isEmpty()) null else SetData(sink.entries.toList())
}
