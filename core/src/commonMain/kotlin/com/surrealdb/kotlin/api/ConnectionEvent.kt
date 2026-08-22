package com.surrealdb.kotlin.api

public sealed class ConnectionEvent {
    public object Connecting : ConnectionEvent()

    public object Connected : ConnectionEvent()

    public object Disconnected : ConnectionEvent()

    public data class Reconnecting(
        val attempt: Int,
        val delayMillis: Long,
    ) : ConnectionEvent()

    public data class Error(
        val cause: Throwable,
    ) : ConnectionEvent()
}
