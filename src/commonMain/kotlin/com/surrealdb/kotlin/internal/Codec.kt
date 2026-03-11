package com.surrealdb.kotlin.internal

import com.surrealdb.kotlin.SurrealClientConfig
import com.surrealdb.kotlin.SurrealHttpCodec
import com.surrealdb.kotlin.error.SurrealProtocolException
import com.surrealdb.kotlin.model.CborRpcError
import com.surrealdb.kotlin.model.CborRpcRequest
import com.surrealdb.kotlin.model.CborRpcResponse
import com.surrealdb.kotlin.model.SurrealRpcRequest
import com.surrealdb.kotlin.model.SurrealRpcResponse
import com.surrealdb.kotlin.model.toCborValue
import com.surrealdb.kotlin.model.toJsonElement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalSerializationApi::class)
internal class SurrealCodec(
    private val config: SurrealClientConfig,
) {
    fun contentTypeHeader(codec: SurrealHttpCodec): String = when (codec) {
        SurrealHttpCodec.JSON -> "application/json"
        SurrealHttpCodec.CBOR -> "application/cbor"
    }

    fun encodeHttpPayload(request: SurrealRpcRequest): ByteArray = when (config.codec) {
        SurrealHttpCodec.JSON -> config.json.encodeToString(request).encodeToByteArray()
        SurrealHttpCodec.CBOR -> {
            val cborRequest = CborRpcRequest(
                id = request.id,
                method = request.method,
                params = request.params.map { it.toCborValue() },
            )
            config.cbor.encodeToByteArray(CborRpcRequest.serializer(), cborRequest)
        }
    }

    fun decodeHttpPayload(body: ByteArray): SurrealRpcResponse = try {
        when (config.codec) {
            SurrealHttpCodec.JSON -> config.json.decodeFromString(SurrealRpcResponse.serializer(), body.decodeToString())
            SurrealHttpCodec.CBOR -> {
                val cborResponse = config.cbor.decodeFromByteArray(CborRpcResponse.serializer(), body)
                SurrealRpcResponse(
                    id = cborResponse.id,
                    result = cborResponse.result?.toJsonElement(),
                    error = cborResponse.error?.let { error ->
                        com.surrealdb.kotlin.model.SurrealRpcError(
                            code = error.code,
                            message = error.message,
                            data = error.data?.toJsonElement(),
                        )
                    },
                )
            }
        }
    } catch (cause: SerializationException) {
        throw SurrealProtocolException("Unable to decode SurrealDB HTTP response", cause)
    }

    fun encodeWsText(request: SurrealRpcRequest): String = config.json.encodeToString(request)

    fun decodeWsText(text: String): SurrealRpcResponse = try {
        config.json.decodeFromString(SurrealRpcResponse.serializer(), text)
    } catch (cause: SerializationException) {
        throw SurrealProtocolException("Unable to decode SurrealDB websocket frame", cause)
    }
}
