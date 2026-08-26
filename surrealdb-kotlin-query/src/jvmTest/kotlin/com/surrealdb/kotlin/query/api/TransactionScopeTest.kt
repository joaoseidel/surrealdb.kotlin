package com.surrealdb.kotlin.query.api

import com.surrealdb.kotlin.core.api.Session
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.beginTransaction
import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.query.api.query.create
import com.surrealdb.kotlin.query.api.query.surql
import com.surrealdb.kotlin.query.runtime.engine.FakeSurrealServer
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun withServer(block: suspend CoroutineScope.(FakeSurrealServer, Session) -> Unit) {
    val server = FakeSurrealServer()
    server.start()
    try {
        val client = Surreal(Surreal.Config(url = "ws://127.0.0.1:${server.port}"))
        try {
            runBlocking { block(server, client.session()) }
        } finally {
            client.close()
        }
    } finally {
        server.stop()
    }
}

private fun FakeSurrealServer.lastQuery(): JsonObject =
    received.last { it["method"]?.jsonPrimitive?.content == "query" }

private fun JsonObject.txn(): String? = this["txn"]?.jsonPrimitive?.content

/**
 * The transaction id travels in the JSON-RPC envelope rather than the statement,
 * so nothing in the SurrealQL says which transaction a statement belongs to.
 * These cases read the envelope the client actually sent.
 */
class TransactionScopeTest :
    ShouldSpec(
        {
            context("a statement sent inside a transaction") {
                should("carry the transaction id, so the server applies it inside the transaction") {
                    withServer { server, db ->
                        val transaction = db.beginTransaction()

                        transaction
                            .create(RecordId("person", "alice"))
                            .content(buildJsonObject { put("name", JsonPrimitive("Alice")) })
                            .await()

                        server.lastQuery().txn() shouldBe transaction.txnId
                        transaction.commit()
                    }
                }

                should("carry it for a raw SurrealQL string too, because that form is derived from the bound one") {
                    withServer { server, db ->
                        val transaction = db.beginTransaction()

                        transaction.query(surql("CREATE person:bob"))

                        server.lastQuery().txn() shouldBe transaction.txnId
                        transaction.commit()
                    }
                }
            }

            context("a statement sent on the session") {
                should("carry no transaction id, so work outside a transaction is never swept into an open one") {
                    withServer { server, db ->
                        db.beginTransaction()

                        db.query(surql("SELECT 1"))

                        server.lastQuery().txn().shouldBeNull()
                    }
                }
            }
        },
    )
