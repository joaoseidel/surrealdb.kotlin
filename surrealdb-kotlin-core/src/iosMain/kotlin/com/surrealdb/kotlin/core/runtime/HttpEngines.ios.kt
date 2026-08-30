package com.surrealdb.kotlin.core.runtime

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual fun defaultHttpClientEngine(): HttpClientEngineFactory<*> = Darwin
