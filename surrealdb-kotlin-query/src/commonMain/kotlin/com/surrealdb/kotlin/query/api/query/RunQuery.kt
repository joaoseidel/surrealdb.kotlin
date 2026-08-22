package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext

/**
 * Builder for SurrealQL function invocations; ports the
 * [run.ts](https://github.com/surrealdb/surrealdb.js/blob/ca8dae20ba439b6b4242ff2822b271a2e5aaaa60/packages/sdk/src/query/run.ts)
 * reference impl.
 *
 * The function name is validated against the same identifier pattern that
 * surrealdb.js uses (`[a-zA-Z0-9_:]+`); the version, if supplied, must be
 * dot-separated digits.
 */
public class RunQuery internal constructor(
    context: QueryContext,
    private val name: String,
    private val version: String? = null,
    private val args: List<Any?> = emptyList(),
) : Query(context) {
    init {
        require(NAME_REGEX.matches(name)) { "Invalid function name: '$name'" }
        if (version != null) {
            require(VERSION_REGEX.matches(version)) {
                "Invalid function version: '$version'"
            }
        }
    }

    public fun version(version: String): RunQuery = RunQuery(context, name, version, args)

    public fun args(vararg args: Any?): RunQuery = RunQuery(context, name, version, args.toList())

    override fun compile(): BoundQuery {
        val q = BoundQuery()
        q.appendLiteral(name)
        if (version != null) q.appendLiteral("<$version>")
        q.appendLiteral("(")
        args.forEachIndexed { i, arg ->
            if (i > 0) q.appendLiteral(", ")
            q.appendValue(arg)
        }
        q.appendLiteral(")")
        return q
    }

    private companion object {
        val NAME_REGEX = Regex("""^[a-zA-Z0-9_:]+$""")
        val VERSION_REGEX = Regex("""^[0-9.]+$""")
    }
}
