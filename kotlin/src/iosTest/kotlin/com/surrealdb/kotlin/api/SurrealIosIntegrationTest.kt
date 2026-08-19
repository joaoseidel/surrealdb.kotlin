package com.surrealdb.kotlin.api

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? = getenv(name)?.toKString()

/**
 * Opt-in integration spec: runs only when `SURREAL_RUN_INTEGRATION=true` names a reachable server,
 * so an ordinary `iosSimulatorArm64Test` does not require one.
 */
class SurrealIosIntegrationTest :
    ShouldSpec({
        context("a real SurrealDB over HTTP, from iOS") {
            should("sign in, select a database and answer a query") {
                if (env("SURREAL_RUN_INTEGRATION") != "true") return@should

                val client =
                    Surreal(
                        Surreal.Config(url = env("SURREAL_IOS_ENDPOINT") ?: "http://127.0.0.1:8000"),
                    )

                client.signin(
                    buildJsonObject {
                        put("user", JsonPrimitive("root"))
                        put("pass", JsonPrimitive("root"))
                    },
                )
                client.use("main", "main")
                val result = client.query("SELECT * FROM person LIMIT 1")

                result.toString().shouldNotBeEmpty()
                client.close()
            }
        }
    })
