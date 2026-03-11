package com.surrealdb.kotlin

import io.ktor.client.HttpClient
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json

public enum class SurrealHttpCodec {
    JSON,
    CBOR,
}

public data class SurrealClientConfig @OptIn(ExperimentalSerializationApi::class) constructor(
    val httpEndpoint: String,
    val wsEndpoint: String? = null,
    val codec: SurrealHttpCodec = SurrealHttpCodec.JSON,
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = true
    },
    val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
    },
    val autoAuthenticate: Boolean = false,
    val credentialProvider: (suspend () -> SurrealAuthInput?)? = null,
    val httpClientFactory: ((SurrealClientConfig) -> HttpClient)? = null,
    val requestTimeoutMillis: Long = 30_000,
)
