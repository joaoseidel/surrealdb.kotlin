package com.surrealdb.kotlin.core.runtime.engine

import com.surrealdb.kotlin.core.api.ConnectionEvent
import com.surrealdb.kotlin.core.api.Feature
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.error.SurrealTransportException
import com.surrealdb.kotlin.core.runtime.RpcRequest
import com.surrealdb.kotlin.core.runtime.RpcResponse
import com.surrealdb.kotlin.core.runtime.codec.Codec
import com.surrealdb.kotlin.core.runtime.normalizeRpcEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile

internal class HttpEngine(
    config: Surreal.Config,
    httpClient: HttpClient,
    codec: Codec,
) : RpcEngine(config, httpClient, codec) {
    override val features: Set<Feature> =
        setOf(
            Feature.ExportImport,
            Feature.SurrealML,
        )

    @Volatile private var started = false

    override suspend fun start() {
        if (started) return
        started = true
        publishEvent(ConnectionEvent.Connected)
    }

    override suspend fun dispatch(
        request: RpcRequest,
        session: SessionSnapshot,
    ): RpcResponse {
        val endpoint = normalizeRpcEndpoint(config.url)
        val payload = codec.encodeHttpPayload(request)
        val contentType = codec.contentTypeHeader()

        val response =
            httpClient.post(endpoint) {
                headers {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.Accept, contentType)
                    session.token?.let { append(HttpHeaders.Authorization, "Bearer $it") }
                    session.namespace?.let { append("Surreal-NS", it) }
                    session.database?.let { append("Surreal-DB", it) }
                }
                setBody(payload)
            }

        val bytes = response.body<ByteArray>()
        if (!response.status.isSuccess()) {
            throw SurrealTransportException(
                "SurrealDB request failed with HTTP ${response.status.value}: ${bytes.decodeToString()}",
            )
        }

        return codec.decodeHttpPayload(bytes)
    }

    override fun close() {
        publishEvent(ConnectionEvent.Disconnected)
    }
}
