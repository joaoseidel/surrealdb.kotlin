package com.surrealdb.kotlin.core.runtime

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

internal actual fun defaultHttpClientEngine(): HttpClientEngineFactory<*> = CIO
