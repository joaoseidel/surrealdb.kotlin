package com.surrealdb.kotlin.core.runtime.codec

import com.surrealdb.kotlin.core.api.Surreal
import com.surrealdb.kotlin.core.api.error.SurrealProtocolException
import com.surrealdb.kotlin.core.runtime.RpcRequest
import com.surrealdb.kotlin.core.runtime.RpcResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class Codec(
    private val config: Surreal.Config,
) {
    // Envelope-only Json: forced explicitNulls=false so optional fields like
    // `txn` are omitted from the wire when absent. Inherits the user-configured
    // serializer settings otherwise so any custom modules still apply.
    private val envelopeJson: Json =
        Json(from = config.json) {
            explicitNulls = false
        }

    fun contentTypeHeader(): String = "application/json"

    fun encodeHttpPayload(request: RpcRequest): ByteArray = envelopeJson.encodeToString(request).encodeToByteArray()

    fun decodeHttpPayload(body: ByteArray): RpcResponse =
        try {
            envelopeJson.decodeFromString(RpcResponse.serializer(), body.decodeToString())
        } catch (cause: SerializationException) {
            throw SurrealProtocolException("Unable to decode SurrealDB HTTP response", cause)
        }

    fun encodeWsText(request: RpcRequest): String = envelopeJson.encodeToString(request)

    fun decodeWsText(text: String): RpcResponse =
        try {
            envelopeJson.decodeFromString(RpcResponse.serializer(), text)
        } catch (cause: SerializationException) {
            throw SurrealProtocolException("Unable to decode SurrealDB websocket frame", cause)
        }
}
