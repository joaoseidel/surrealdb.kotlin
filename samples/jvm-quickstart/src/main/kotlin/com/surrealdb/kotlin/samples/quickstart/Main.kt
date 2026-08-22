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
import com.surrealdb.kotlin.api.query.update
import com.surrealdb.kotlin.samples.quickstart.tables.People
import com.surrealdb.kotlin.samples.quickstart.tables.toPersonList
import kotlinx.coroutines.runBlocking

private suspend fun quickstart(endpoint: String) {
    val client = Surreal(Surreal.Config(url = endpoint))

    client.use { client ->
        val db = client.session()
        db.signin(Credentials.RootUser("root", "root"))
        db.use(Namespace("main"), Database("main"))

        db
            .update(People["ada"])
            .set {
                it[name] = "Ada Lovelace"
                it[age] = 36
                it[address.city] = "London"
            }.await()

        db.merge(People["ada"]) { it[displayName] = "Ada" }.await()
        db.patch(People["ada"]) { it.replace(age, 37) }.await()

        val people =
            db
                .select(People)
                .await()
                .toPersonList()
        println("decoded ${people.size} people")

        val minimumAge = 18
        val raw =
            surqlTemplate {
                "SELECT * FROM person WHERE age >= ${bind(minimumAge)}"
            }
        db.query(raw)

        val live = db.live(People)
        live.cancel()
    }
}

fun main(): Unit =
    runBlocking {
        quickstart(System.getenv("SURREAL_ENDPOINT") ?: "ws://127.0.0.1:8000")
    }
