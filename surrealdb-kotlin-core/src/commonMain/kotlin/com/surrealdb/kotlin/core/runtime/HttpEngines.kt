package com.surrealdb.kotlin.core.runtime

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * The HTTP engine this driver talks over, named rather than discovered.
 *
 * Ktor will otherwise pick an engine by service loading, which resolves against whatever the
 * *consuming* application happens to carry. That is not a choice a driver can leave to chance: an
 * application with `ktor-client-apache5` on its classpath, for one, hands this driver an engine
 * with no WebSocket support at all, and every RPC then waits forever on a reply that cannot come.
 *
 * A caller who needs a different engine, or a client configured their own way, supplies one through
 * [com.surrealdb.kotlin.core.api.Surreal.Config.httpClientFactory].
 */
internal expect fun defaultHttpClientEngine(): HttpClientEngineFactory<*>
