package com.surrealdb.kotlin.core.runtime.engine

import com.surrealdb.kotlin.core.api.ConnectionEvent
import com.surrealdb.kotlin.core.api.Feature
import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.error.ErrorKind
import com.surrealdb.kotlin.core.api.error.SurrealAuthenticationException
import com.surrealdb.kotlin.core.api.error.SurrealProtocolException
import com.surrealdb.kotlin.core.api.error.SurrealRpcException
import com.surrealdb.kotlin.core.api.error.SurrealTransportException
import com.surrealdb.kotlin.core.runtime.RpcError
import com.surrealdb.kotlin.core.runtime.RpcRequest
import com.surrealdb.kotlin.core.runtime.RpcResponse
import com.surrealdb.kotlin.core.runtime.codec.Codec
import com.surrealdb.kotlin.core.runtime.httpBaseUrl
import com.surrealdb.kotlin.core.runtime.normalizeRpcEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile

internal class HttpEngine(
    config: Surreal.Config,
    httpClient: HttpClient,
    codec: Codec,
) : RpcEngine(config, httpClient, codec) {
    override val features: Set<Feature> = setOf(Feature.ExportImport)

    @Volatile
    private var started = false

    override suspend fun start() {
        if (started) return
        started = true
        publishEvent(ConnectionEvent.Connected)
    }

    override suspend fun query(
        sql: String,
        vars: JsonObject?,
        session: SessionSnapshot,
        txn: String?,
    ): JsonElement {
        val bound = (session.variables + vars.orEmpty()).takeIf { it.isNotEmpty() }?.let(::JsonObject)
        return super.query(sql, bound, session, txn)
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
                }
                sessionHeaders(session)
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

    override suspend fun exportSurql(session: SessionSnapshot): String {
        val response =
            httpClient.get("${httpBaseUrl(config.url)}/export") {
                sessionHeaders(session)
            }
        return response.bytesOrThrow().decodeToString()
    }

    override suspend fun importSurql(
        surql: String,
        session: SessionSnapshot,
    ) {
        val response =
            httpClient.post("${httpBaseUrl(config.url)}/import") {
                headers {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.Accept, "application/json")
                }
                sessionHeaders(session)
                setBody(surql.encodeToByteArray())
            }
        val failures = decodeImportFailures(response.bytesOrThrow())
        if (failures.isEmpty()) return
        val first = failures.first().jsonObject
        val message = first["result"]?.jsonPrimitive?.contentOrNull ?: "import failed"
        throw mapRpcError(
            RpcError(
                message =
                    if (failures.size == 1) {
                        message
                    } else {
                        "${failures.size} statements failed during the import; the first: $message"
                    },
                kind = first["kind"]?.jsonPrimitive?.contentOrNull,
                details = first["details"],
                data = failures,
            ),
        )
    }

    private fun HttpRequestBuilder.sessionHeaders(session: SessionSnapshot) {
        headers {
            session.token?.let { append(HttpHeaders.Authorization, "Bearer $it") }
            session.namespace?.let { append("Surreal-NS", it) }
            session.database?.let { append("Surreal-DB", it) }
        }
    }

    private suspend fun HttpResponse.bytesOrThrow(): ByteArray {
        val bytes = body<ByteArray>()
        if (status.isSuccess()) return bytes
        val text = bytes.decodeToString()
        val body = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val information = body?.get("information")?.jsonPrimitive?.contentOrNull ?: text
        throw when {
            status == HttpStatusCode.Unauthorized -> {
                SurrealAuthenticationException(
                    code = status.value,
                    message = information,
                    data = body,
                    kind =
                        ErrorKind.NotAllowed(
                            ErrorKind.NotAllowed.Detail.Auth(ErrorKind.NotAllowed.AuthReason.InvalidAuth),
                        ),
                )
            }

            status == HttpStatusCode.Forbidden -> {
                SurrealAuthenticationException(
                    code = status.value,
                    message = information,
                    data = body,
                    kind = ErrorKind.NotAllowed(),
                )
            }

            body != null -> {
                SurrealRpcException(
                    code = status.value,
                    message = information,
                    data = body,
                    kind =
                        if (status == HttpStatusCode.BadRequest) {
                            ErrorKind.Validation(ErrorKind.Validation.Detail.InvalidRequest)
                        } else {
                            ErrorKind.Internal
                        },
                )
            }

            else -> {
                SurrealTransportException("SurrealDB request failed with HTTP ${status.value}: $text")
            }
        }
    }

    private fun decodeImportFailures(bytes: ByteArray): JsonArray =
        try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonArray
                ?: throw SurrealProtocolException("Expected a list of failed statements from /import")
        } catch (cause: SerializationException) {
            throw SurrealProtocolException("Unable to decode the SurrealDB import response", cause)
        }

    override fun close() {
        publishEvent(ConnectionEvent.Disconnected)
    }
}
