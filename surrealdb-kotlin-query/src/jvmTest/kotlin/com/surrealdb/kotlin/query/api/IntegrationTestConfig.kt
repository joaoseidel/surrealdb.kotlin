package com.surrealdb.kotlin.query.api

import io.kotest.core.test.config.DefaultTestConfig

internal val integrationTestConfig =
    DefaultTestConfig(enabledIf = { System.getenv("SURREAL_RUN_INTEGRATION") == "true" })

internal fun integrationEndpoint(): String = System.getenv("SURREAL_JVM_ENDPOINT") ?: "http://127.0.0.1:8000"
