package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.Target
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.query.api.data.Field
import com.surrealdb.kotlin.query.api.data.Table
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import kotlin.time.Instant

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
     * [com.surrealdb.kotlin.query.api.data.TableRecord] for link fields would make
     * every mismatch above report against both candidates, which buries the
     * message that matters. Assign a link through its
     * [com.surrealdb.kotlin.query.api.data.TableRecord.record] instead.
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
     * still renders as `type::record(…)` and a datetime as `type::datetime(…)`
     * rather than as an object or a string that the field's type refuses.
     * Everything else
     * is encoded with the context's [Json], which is what lets a composite be
     * assigned without the field carrying a serializer.
     */
    @PublishedApi
    internal inline fun <reified V> encode(value: V): Any? =
        when (value) {
            is Target, is Instant -> value
            else -> json.encodeToJsonElement(serializer<V>(), value)
        }

    /**
     * Assign what SurrealDB computes rather than a value:
     *
     * ```
     * set { it.raw(password) { "crypto::argon2::generate(${bind(secret)})" } }
     * ```
     *
     * A server-side function is the one thing a typed assignment cannot carry,
     * because its result does not exist until the statement runs. Values inside
     * it still bind, so the caller's input never reaches the statement as text.
     */
    public fun raw(
        field: Field<*>,
        build: SurqlTemplate.() -> String,
    ) {
        record(field, "=", RawExpression(surqlTemplate(build)))
    }

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
        private val sink: Assignments,
        private val field: Field<List<E>>,
        private val encode: (E) -> Any?,
    ) {
        /** Append [value] to the array. */
        public operator fun plusAssign(value: E) {
            sink.record(field, "+=", encode(value))
        }

        /** Remove [value] from the array. */
        public operator fun minusAssign(value: E) {
            sink.record(field, "-=", encode(value))
        }

        /**
         * Hold [value] in the array, once, with SurrealQL's `array::union`.
         *
         * SurrealQL's `+=` appends whatever it is handed, so a reader who opens
         * a second tab is listed twice and the first close removes only one of
         * them. This is the set spelling of the same intent, and it is a no-op
         * when the value is already there.
         */
        public fun include(value: E) {
            sink.record(field, "=", ArrayFunction("array::union", encode(value)))
        }

        /**
         * Hold [value] no longer, however many times the array holds it. The
         * set counterpart of [include].
         */
        public fun exclude(value: E) {
            sink.record(field, "=", ArrayFunction("array::complement", encode(value)))
        }
    }

/** An expression SurrealDB evaluates, with the values inside it already bound. */
internal class RawExpression(
    val fragment: BoundQuery,
)

/**
 * An assignment that reads the field it writes: `f = fn(f ?? [], [value])`.
 *
 * The `?? []` is what lets it write a record that has no such field yet, which
 * is what `+=` and `-=` do on their own.
 */
internal class ArrayFunction(
    val function: String,
    val element: Any?,
)

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
            val path = entry.field.path.value
            into.appendLiteral(path)
            when (val value = entry.value) {
                is ArrayFunction -> {
                    into.appendLiteral(" = ${value.function}($path ?? [], [")
                    into.appendValue(value.element)
                    into.appendLiteral("])")
                }

                is RawExpression -> {
                    into.appendLiteral(" ${entry.op} ")
                    into.append(value.fragment)
                }

                else -> {
                    into.appendLiteral(" ${entry.op} ")
                    into.appendValue(value)
                }
            }
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
