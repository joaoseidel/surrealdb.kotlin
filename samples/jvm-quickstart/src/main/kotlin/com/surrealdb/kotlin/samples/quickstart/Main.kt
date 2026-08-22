package com.surrealdb.kotlin.samples.quickstart

import com.surrealdb.kotlin.api.Credentials
import com.surrealdb.kotlin.api.Database
import com.surrealdb.kotlin.api.Namespace
import com.surrealdb.kotlin.api.Surreal
import com.surrealdb.kotlin.api.data.get
import com.surrealdb.kotlin.api.query.merge
import com.surrealdb.kotlin.api.query.patch
import com.surrealdb.kotlin.api.query.select
import com.surrealdb.kotlin.api.query.surqlTemplate
import com.surrealdb.kotlin.api.query.upsert
import com.surrealdb.kotlin.samples.quickstart.tables.People
import com.surrealdb.kotlin.samples.quickstart.tables.toPersonList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
            }.await()

        println("Updating `person:ada` `display_name` to Ada...")
        db
            .merge(People["ada"]) {
                it[displayName] = "Ada"
            }.await()

        println("Updating `person:ada` `age` to 37...")
        db
            .patch(People["ada"]) {
                it.replace(age, 37)
            }.await()

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
        val live = db.live(People)
        try {
            withTimeoutOrNull(10.seconds) {
                live.events.collect { println("Live event: $it") }
            }
        } finally {
            live.cancel()
            println("Live query cancelled.")
        }
    }
}

fun main(): Unit =
    runBlocking {
        quickstart(System.getenv("SURREAL_ENDPOINT") ?: "ws://127.0.0.1:8000")
    }
