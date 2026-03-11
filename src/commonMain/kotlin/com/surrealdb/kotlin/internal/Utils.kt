package com.surrealdb.kotlin.internal

import kotlin.random.Random

internal fun randomRequestId(): String {
    val now = Random.nextInt().toUInt().toString(16)
    val random = Random.nextLong().toString(16)
    return "$now-$random"
}

internal fun normalizeRpcEndpoint(endpoint: String): String {
    val trimmed = endpoint.trimEnd('/')
    return if (trimmed.endsWith("/rpc")) trimmed else "$trimmed/rpc"
}

internal fun defaultWsEndpoint(httpEndpoint: String): String {
    val rpc = normalizeRpcEndpoint(httpEndpoint)
    return when {
        rpc.startsWith("https://") -> "wss://${rpc.removePrefix("https://")}"
        rpc.startsWith("http://") -> "ws://${rpc.removePrefix("http://")}"
        rpc.startsWith("wss://") || rpc.startsWith("ws://") -> rpc
        else -> "ws://$rpc"
    }
}
