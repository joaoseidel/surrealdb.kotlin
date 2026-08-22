package com.surrealdb.kotlin.core.api

import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A client-side SurrealDB transaction.
 *
 * Created by [Session.beginTransaction] (or the [transaction] block
 * extension). It is a [QueryContext], so the full CRUD API is available
 * inside the block and every statement is automatically scoped to this
 * transaction; the SDK passes the transaction id in the JSON-RPC envelope's
 * `txn` field, and the server applies the statement inside the transaction
 * without an explicit `BEGIN` round-trip.
 *
 * Either call [commit] or [cancel] before discarding; leaving a transaction
 * open will keep server resources allocated until it times out.
 */
public class Transaction internal constructor(
    private val session: Session,
    public val txnId: String,
) : QueryContext {
    override val json: Json get() = session.json

    override suspend fun query(bound: BoundQuery): JsonElement =
        session.controller.query(
            sessionId = session.sessionId,
            sql = bound.surql,
            vars = bound.bindingsAsJsonObject().takeIf { it.isNotEmpty() },
            txn = txnId,
        )

    /** Commit the transaction. */
    public suspend fun commit() {
        session.controller.commit(session.sessionId, txnId)
    }

    /** Cancel the transaction, discarding all changes. */
    public suspend fun cancel() {
        session.controller.cancel(session.sessionId, txnId)
    }
}

/**
 * Eagerly begin a transaction. The caller owns the lifecycle and must call
 * [Transaction.commit] or [Transaction.cancel].
 */
public suspend fun Session.beginTransaction(): Transaction = Transaction(this, controller.begin(sessionId))

/**
 * Run [block] inside a transaction. Commits on normal completion; cancels and
 * rethrows if the block throws.
 */
public suspend fun Session.transaction(block: suspend Transaction.() -> Unit) {
    val tx = beginTransaction()
    try {
        tx.block()
        tx.commit()
    } catch (t: Throwable) {
        runCatching { tx.cancel() }
        throw t
    }
}
