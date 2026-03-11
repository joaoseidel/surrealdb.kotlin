# surrealdb.kotlin

Kotlin Multiplatform SurrealDB Driver for:
- Android
- JVM (server)
- iOS (arm64, x64, simulator)

## Features
- HTTP RPC client (`POST /rpc`) for core operations.
- Optional WebSocket transport for live queries.
- JSON and CBOR support on HTTP transport.
- JSON on WebSocket transport.
- Coroutines `Flow` API for live query streams.
- Manual and automatic auth retry (credential-provider callback).

## Supported RPC wrappers
- `ping`, `version`, `use`, `info`
- `signup`, `signin`, `authenticate`, `invalidate`
- `let`, `unset`, `query`, `select`, `create`, `insert`, `update`, `merge`, `patch`, `delete`
- `relate`, `run`, `live`, `kill`

## Quick Start

```kotlin
val client = SurrealClient(
    SurrealClientConfig(
        httpEndpoint = "http://localhost:8000",
        wsEndpoint = "ws://localhost:8000/rpc", // optional
        codec = SurrealHttpCodec.JSON,
    )
)

client.signin(buildJsonObject {
    put("user", JsonPrimitive("root"))
    put("pass", JsonPrimitive("root"))
})
client.use("main", "main")

val records = client.query("SELECT * FROM person")
```

Live query usage:

```kotlin
val live = client.live("LIVE SELECT * FROM person")

val job = scope.launch {
    live.events.collect { event ->
        println("${event.action}: ${event.result}")
    }
}

// later
live.cancel()
job.cancel()
```

## Tests

Unit tests:

```bash
./gradlew test
```

Run JVM integration tests with Docker:

```bash
SURREAL_RUN_INTEGRATION=true ./gradlew jvmTest
```

Mobile integration tests are opt-in and expect a reachable SurrealDB endpoint:
- Android default endpoint: `http://10.0.2.2:8000`
- iOS default endpoint: `http://127.0.0.1:8000`

## Notes
- Embedded mode is intentionally not included in this release.
- Live queries are fail-fast on websocket disconnect (no auto-resubscribe).
