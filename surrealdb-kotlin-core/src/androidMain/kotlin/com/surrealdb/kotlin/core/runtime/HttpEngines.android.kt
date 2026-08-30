package com.surrealdb.kotlin.core.runtime

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun defaultHttpClientEngine(): HttpClientEngineFactory<*> = OkHttp
