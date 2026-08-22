package com.surrealdb.kotlin.query.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.surrealdb.kotlin.core.api.Credentials
import com.surrealdb.kotlin.core.api.Database
import com.surrealdb.kotlin.core.api.Namespace
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.query.api.data.Table
import com.surrealdb.kotlin.query.api.query.create
import com.surrealdb.kotlin.query.api.query.delete
import com.surrealdb.kotlin.query.api.query.surql
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurrealAndroidIntegrationTest {
    private val rootCredentials = Credentials.RootUser("root", "root")

    private fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    private fun requireIntegration() = assumeTrue(argument("SURREAL_RUN_INTEGRATION") == "true")

    private fun endpoint(): String = argument("SURREAL_ANDROID_ENDPOINT") ?: "http://10.0.2.2:8000"

    @Test
    fun rpcOverHttp(): Unit =
        runBlocking {
            requireIntegration()

            val client = Surreal(Surreal.Config(url = endpoint()))
            val db = client.session()

            try {
                db.signin(rootCredentials)
                db.use(Namespace("main"), Database("main"))
                db.ping()

                db.query(surql("DEFINE TABLE android_person SCHEMALESS"))
                db.query(surql("DELETE android_person"))

                db
                    .create(RecordId("android_person", "ada"))
                    .content(buildJsonObject { put("name", JsonPrimitive("Ada")) })
                    .await()

                val rows =
                    db
                        .query(surql("SELECT * FROM android_person"))
                        .jsonArray[0]
                        .jsonObject["result"]!!
                        .jsonArray

                assertEquals(1, rows.size)
                assertEquals(
                    "Ada",
                    rows[0]
                        .jsonObject["name"]!!
                        .jsonPrimitive.content,
                )

                db.delete(RecordId("android_person", "ada")).await()
            } finally {
                client.close()
            }
        }

    @Test
    fun liveQueryOverWebSocket(): Unit =
        runBlocking {
            requireIntegration()

            val wsEndpoint =
                endpoint()
                    .replace("http://", "ws://")
                    .replace("https://", "wss://")
            val client = Surreal(Surreal.Config(url = wsEndpoint, autoConnect = true))
            val db = client.session()

            try {
                db.signin(rootCredentials)
                db.use(Namespace("main"), Database("main"))
                db.query(surql("DEFINE TABLE android_live SCHEMALESS"))
                db.query(surql("DELETE android_live"))

                val subscription = db.live(Table("android_live"))
                withTimeout(10_000) { client.activeLiveQueries.first { subscription.id in it } }

                db
                    .create(RecordId("android_live", "one"))
                    .content(buildJsonObject { put("name", JsonPrimitive("Live")) })
                    .await()

                val event = withTimeout(10_000) { subscription.events.first() }

                assertEquals("CREATE", event.action)

                subscription.cancel()
                db.delete(RecordId("android_live", "one")).await()
            } finally {
                client.close()
            }
        }
}
