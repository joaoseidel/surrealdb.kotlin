package com.surrealdb.kotlin.api

import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.data.get
import com.surrealdb.kotlin.api.live.LiveQueryEvent
import com.surrealdb.kotlin.api.query.create
import com.surrealdb.kotlin.api.query.delete
import com.surrealdb.kotlin.api.query.surql
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private object LiveUsers : Table("filtered_live_user") {
    val age by field<Int>()
}

private fun onLiveServer(block: suspend (Surreal, Session) -> Unit) {
    runBlocking {
        val endpoint = integrationEndpoint().replace("http://", "ws://").replace("https://", "wss://")
        val client = Surreal(Surreal.Config(url = endpoint))
        val db = client.session()
        try {
            db.signin(Credentials.RootUser("root", "root"))
            db.use(Namespace("main"), Database("main"))
            db.query(surql("DEFINE TABLE ${LiveUsers.tableName} SCHEMALESS"))
            db.query(surql("DELETE ${LiveUsers.tableName}"))

            block(client, db)
        } finally {
            client.close()
        }
    }
}

class FilteredLiveIntegrationTest :
    ShouldSpec({
        defaultTestConfig = integrationTestConfig

        context("Session.live(table, filter)") {
            should("bind the filter and receive only records that match it") {
                onLiveServer { client, db ->
                    val received = Channel<LiveQueryEvent<Row>>(Channel.UNLIMITED)
                    val collector = launch { db.live(LiveUsers) { age greaterEq 18 }.collect { received.send(it) } }
                    withTimeout(5_000) { client.activeLiveQueries.first { it.isNotEmpty() } }

                    db.create(LiveUsers["minor"]).set { it[age] = 17 }.await()
                    db.create(LiveUsers["adult"]).set { it[age] = 18 }.await()

                    val event = withTimeout(10_000) { received.receive() }
                    event.shouldBeInstanceOf<LiveQueryEvent.Created<Row>>().value[LiveUsers.age] shouldBe 18
                    received.tryReceive().isSuccess shouldBe false

                    collector.cancelAndJoin()
                    db.delete(LiveUsers).await()
                }
            }
        }
    })
