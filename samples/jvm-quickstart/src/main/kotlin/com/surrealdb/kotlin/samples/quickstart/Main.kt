package com.surrealdb.kotlin.samples.quickstart

import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.query.api.data.get
import com.surrealdb.kotlin.query.api.query.merge
import com.surrealdb.kotlin.query.api.query.patch
import com.surrealdb.kotlin.query.api.query.select
import com.surrealdb.kotlin.query.api.query.surqlTemplate
import com.surrealdb.kotlin.query.api.query.update
import com.surrealdb.kotlin.query.api.query.upsert
import com.surrealdb.kotlin.samples.quickstart.tables.People
import com.surrealdb.kotlin.samples.quickstart.tables.toPersonList
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

private suspend fun quickstart(endpoint: String) {
    val client = Surreal(Surreal.Config(url = endpoint))

    client.use { client ->
        println("Creating SurrealDB session...")
        val db = client.session()
        db.signin(Credentials.RootUser("root", "root"))
        db.use(Namespace("main"), Database("main"))

        println("Inserting `person:ada` record...")
        db
            .upsert(People["ada"])
            .set {
                it[name] = "Ada Lovelace"
                it[age] = 36
                it[address.city] = "London"
            }.only()
            .await()

        println("Updating `person:ada` `display_name` to Ada...")
        db
            .merge(People["ada"]) {
                it[displayName] = "Ada"
            }.only()
            .await()

        println("Updating `person:ada` `age` to 37...")
        db
            .patch(People["ada"]) {
                it.replace(age, 37)
            }.only()
            .await()

        println("Selecting `person` records...")
        val people =
            db
                .select(People)
                .await()
                .toPersonList()
        println("People: $people")

        println("Executing raw query...")
        val minimumAge = 18
        val raw =
            surqlTemplate {
                "SELECT * FROM person WHERE age >= ${bind(minimumAge)}"
            }
        val rawResult = db.query(raw)
        println("Raw result: $rawResult")

        println("Live query for 10 seconds...")
        coroutineScope {
            val eventJob =
                launch {
                    db.live(People) { age greaterEq 18 }.collect { println("Live event: $it") }
                }

            try {
                db
                    .upsert(People["bob"])
                    .set {
                        it[name] = "Bob"
                        it[age] = 40
                        it[displayName] = "Bob"
                        it[address.city] = "Paris"
                    }.only()
                    .await()

                db
                    .update(People["ada"])
                    .set {
                        it[displayName] = "Ada Lovelace"
                    }.only()
                    .await()

                delay(10.seconds)
            } finally {
                eventJob.cancelAndJoin()
            }
        }
    }
}

fun main(): Unit =
    runBlocking {
        quickstart(System.getenv("SURREAL_ENDPOINT") ?: "ws://127.0.0.1:8000")
    }
