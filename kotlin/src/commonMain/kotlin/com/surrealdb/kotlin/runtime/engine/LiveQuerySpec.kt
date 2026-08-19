package com.surrealdb.kotlin.runtime.engine

/**
 * How a live query was started, kept so the engine can start it again on a new
 * socket. A SurrealDB server forgets its live queries when the session behind
 * them goes, so a reconnect has to re-run the original statement rather than
 * resume anything.
 */
internal sealed interface LiveQuerySpec {
    data class Table(
        val table: String,
        val diff: Boolean?,
    ) : LiveQuerySpec

    data class Statement(
        val sql: String,
    ) : LiveQuerySpec
}

/**
 * The spec plus a way to read the session it belongs to. The session is read
 * again at re-issue time rather than captured, so a token renewed while the
 * connection was down is the one the new statement is sent under.
 */
internal class LiveQuerySource(
    val spec: LiveQuerySpec,
    val session: suspend () -> SessionSnapshot,
)
