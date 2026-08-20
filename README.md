# surrealdb.kotlin

Kotlin Multiplatform SurrealDB driver for:

- Android
- JVM (server)
- iOS (arm64, x64, simulator)

API surface and behaviour mirror [surrealdb.js v2.0.3](https://github.com/surrealdb/surrealdb.js), which is the cross-SDK reference.

## Features

- Single-URL connection. The engine is selected from the protocol: `http://` or `https://` use the HTTP engine; `ws://` or `wss://` use the WebSocket
  engine.
- Two engines with explicit capability sets (`Feature`). Live queries require a WebSocket URL.
- WebSocket reconnection with configurable exponential backoff and pending-call replay across drops.
- Client and session are separate: a `Surreal` owns the connection, and `client.session()` hands out sessions that share it and nothing else — each
  with its own namespace, database, auth token and variables.
- Connection lifecycle exposed as a `SharedFlow<ConnectionEvent>` (`Connecting`, `Connected`, `Disconnected`, `Reconnecting`, `Error`).
- Auto authentication: an optional `credentialProvider` callback re-signs in and retries on auth failure.
- JWT auto-renewal: when a signin response carries a refresh token, renewal is scheduled before the access token's `exp` claim.
- Client-side transactions via `begin` / `commit` / `cancel` RPCs with the transaction id carried in the JSON-RPC envelope's `txn` field — every CRUD
  method inside the block is automatically scoped to that transaction.
- Coroutines `Flow` API for live query notifications.
- Fluent query builder DSL: `db.select(Users).where { age greater 18 }.limit(10).await()`. Every CRUD operation names what it
  acts on with a `Table`, `RecordId` or `RecordIdRange` — the `Target` type, so
  `select("user")` cannot compile into a query for the *string* `"user"` — then
  compiles to local SurrealQL with bound parameters and dispatches via the
  `query` RPC, mirroring [surrealdb.js v2.0.3](https://github.com/surrealdb/surrealdb.js).
- One declaration per table. `object People : Table("person")` names the fields once, with no wire
  type beside it. Inside `where { }` they come from that declaration and each operator is typed, so
  `age greater "18"` does not compile. The same declaration reads the result back: `row[People.name]`
  is a `String`, and `db.checkSchema(People)` asks a SCHEMAFULL database whether it agrees, naming
  every field it does not have.
- [Spectron](#spectron) client for memory and knowledge management, shipped as a separate opt-in artifact (`com.surrealdb:kotlin-spectron`).

## Supported RPC methods

The driver speaks JSON-RPC over both HTTP and WebSocket. The transport is picked from the URL scheme.

- Server: `ping`, `version`, `use`
- Auth: `signup`, `signin`, `authenticate`, `invalidate`, `reset`
- Session variables: `let`, `unset`
- Queries: `query`
- Transactions: `begin`, `commit`, `cancel` (WebSocket only)
- Live: `live`, `kill` (WebSocket only)

CRUD operations (`select`, `create`, `update`, `upsert`, `merge`, `patch`, `delete`, `relate`, `insert`, `insertRelation`, `run`) are not dedicated
RPC methods — they compile locally to SurrealQL and dispatch through `query`. This matches
the [surrealdb.js](https://github.com/surrealdb/surrealdb.js/tree/main/packages/sdk/src/query) approach and keeps the wire protocol slim.

Every builder ends in one of two terminals. `await()` returns `List<Row>` and `awaitSingleOrNull()`
returns `Row?`, whatever the statement pointed at: SurrealDB answers a table with a list, a record id
with the record itself, and a statement that matched nothing with null. `decodeAs<T>()` in front of
either one decodes each record into a type of your own instead. Every call throws on failure; a
caller who wants a `Result` writes `runCatching { session.ping() }`.

Every write verb takes a `returnMode`, which is the SurrealQL
`RETURN NONE | BEFORE | AFTER | DIFF | <fields>` clause. That covers `create`, `update`, `upsert`, `merge`, `patch`, `delete` and `relate`.

```kotlin
db.update(People).content(data).returnMode(ReturnMode.Fields(listOf(People.name))).await()
db.patch(People, patches).returnMode(ReturnMode.Diff).decodeAs<List<JsonObject>>().await()
```

`RETURN DIFF` is the one mode that does not answer with records. It answers with a list of JSON Patch
operations per record, so it is read through `decodeAs` rather than `await()`.

## Install

```kotlin
dependencies {
    implementation("com.surrealdb:kotlin:1.0.0")
}
```

One coordinate covers every target. The Gradle module metadata a Kotlin
Multiplatform publication carries picks `kotlin-jvm`, `kotlin-android` or the
matching `kotlin-ios*` variant for whatever is asking.

Nothing is published under `com.surrealdb:kotlin` yet, so until the first
release, build it and take it from your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

That writes `com.surrealdb:kotlin:1.0.0` and `com.surrealdb:kotlin-spectron:1.0.0`
into `~/.m2/repository`, which a consuming build reaches by listing
`mavenLocal()` among its repositories.

## Quick start

```kotlin
val client = Surreal(Surreal.Config(url = "http://localhost:8000"))
val db = client.session()

db.signin(buildJsonObject {
    put("user", JsonPrimitive("root"))
    put("pass", JsonPrimitive("root"))
})
db.use("main", "main")

// Raw SurrealQL, which answers with the [{ status, result }] envelope as it arrived
val envelope = db.query("SELECT * FROM person")

// Or the fluent builder
object People : Table("person") {
    val id = recordId()
    val name by field<String>()
    val age by field<Int>()
}

val adults: List<Row> = db
    .select(People)
    .where { age greaterEq 18 }
    .limit(50)
    .await()

adults.first()[People.name]   // String
adults.first()[People.id]     // RecordId
```

A row is read through the fields the table declared, so the value type comes from the field and
`val n: Int = row[People.name]` does not compile. A caller with a type of their own names it before
the terminal:

```kotlin
@Serializable
data class Person(val id: RecordId, val name: String, val age: Int)

val people: List<Person> = db
    .select(People)
    .where { age greaterEq 18 }
    .decodeAs<Person>()
    .await()
```

A statement takes the SurrealQL `ONLY` keyword from what it points at. A record id answers with
the record; a table or a record-id range answers with a list of them. `only()` asks for `ONLY` on a
target that would not have taken it, and the server rejects the statement the moment a second record
matches, so a table target needs a limit of its own:

```kotlin
val one: Row? = db
    .select(People)
    .where { age greaterEq 18 }
    .limit(1)
    .only()
    .awaitSingleOrNull()
```

`awaitSingleOrNull()` reads either answer. It throws rather than picking one when the statement
answered with two records, because that is a query that asked the wrong question.

`create` is the exception: it writes exactly one record, so it always carries `ONLY`.

The table declaration is the only one the driver needs. `Person` above is the caller's own type, and
nothing makes the two agree. A field of a nested object is declared by its path, and so is the object
itself when you want to assign or read the whole of it:

```kotlin
object People : Table("person") {
    val address = field<Address>("address")
    val city = field<String>("address.city")
}

db.select(People).where { city eq "Cambridge" }
db.update(People).set { it[city] = "Cambridge" }
row[People.city]      // "Cambridge", because SurrealDB rebuilds the nesting rather than flattening it
```

Whether the database has those fields is a separate question, and `checkSchema` is what asks it. A
`WHERE` naming a field the record does not have is answered with an empty result set rather than an
error, so a misspelt name is a query that silently matches nothing:

```kotlin
db.checkSchema(People) shouldBe emptyList()
// "person.agee is not defined on the server. Known fields: age, name."
```

Only a SCHEMAFULL table can answer. `INFO FOR TABLE` reports no fields at all for a SCHEMALESS one,
and reports that same empty shape for a table nobody has defined, so `checkSchema` returns a message
saying so rather than counting it as agreement.

`Table("person")` on its own is still a target for every verb. It has no fields to name, so a
`where { }` over one is written with the `raw { }` escape hatch, which binds its interpolated values
as parameters like everything else.

Switch to WebSocket transport simply by changing the URL scheme:

```kotlin
val client = Surreal(Surreal.Config(url = "ws://localhost:8000"))
```

## Live queries

`live(table)` subscribes to changes on a single table and returns a subscription whose `events` is a `Flow`:

```kotlin
val sub = db.live(Table("person"))

val job = scope.launch {
    sub.events.collect { event ->
        println("${event.action}: ${event.result}")
    }
}

// later
db.kill(sub.id)
sub.cancel()
job.cancel()
```

The `live` RPC accepts a table and nothing else. It answers a record id or a range with
`Cannot execute LIVE statement using value: …`, so `live` takes `Table` rather than the `Target` the CRUD verbs take, and those two cases fail to compile.

`LiveMode` chooses what each notification carries:

```kotlin
db.live(Table("person"))                   // {"id": "person:one", "name": "Ada"}
db.live(Table("person"), LiveMode.Diffs)   // [{"op": "replace", "path": "/name", "value": "Ada"}]
```

For complex `LIVE SELECT` queries with `WHERE` clauses, run the SurrealQL through `query("LIVE SELECT ...")` to obtain the live UUID.

## Multi-session

One connection can host many sessions, each with its own namespace, database, auth token, and variables:

```kotlin
val tenantA = client.session()
val tenantB = client.session()

tenantA.signin(buildJsonObject { put("user", JsonPrimitive("a")) })
tenantA.use("ns", "db")

tenantB.signin(buildJsonObject { put("user", JsonPrimitive("b")) })
tenantB.use("ns", "db")

tenantA.query("SELECT * FROM person")  // runs as tenant A
tenantB.query("SELECT * FROM person")  // runs as tenant B, isolated

client.closeSession(tenantA)
```

The client is not itself a session, so there is no ambient one for a query to pick up by accident — a single-tenant caller opens one `session()` and
holds it, exactly as a multi-tenant one opens several.

## Transactions

Transactions are client-side: the SDK sends a `begin` RPC, captures the returned transaction id, and tags every subsequent `query` / CRUD-builder
dispatch with that id in the JSON-RPC envelope's `txn` field. `commit` or `cancel` closes it. Requires a WebSocket URL.

Block form (commits on success, cancels on throw):

```kotlin
db.transaction {
    create(RecordId("person", "tx")).content(buildJsonObject { put("name", JsonPrimitive("Tx")) }).await()
    update(RecordId("counter", "1")).content(buildJsonObject { put("hits", JsonPrimitive(2)) }).await()
}
```

Explicit form for cases where you need finer control:

```kotlin
val tx = db.beginTransaction()
try {
    tx.create(Table("person")).content(buildJsonObject { put("name", JsonPrimitive("Ada")) }).await()
    tx.commit()
} catch (cause: Throwable) {
    tx.cancel()
    throw cause
}
```

## Connection events

```kotlin
scope.launch {
    client.connectionEvents.collect { event ->
        when (event) {
            is ConnectionEvent.Connecting -> println("connecting")
            is ConnectionEvent.Connected -> println("connected")
            is ConnectionEvent.Disconnected -> println("disconnected")
            is ConnectionEvent.Reconnecting ->
                println("retry ${event.attempt} in ${event.delayMillis}ms")
            is ConnectionEvent.Error -> println("error ${event.cause.message}")
        }
    }
}
```

## Configuration

```kotlin
Surreal.Config(
    url = "wss://example.com",
    autoConnect = true,
    requestTimeoutMillis = 30_000,
    reconnect = ReconnectConfig(
        enabled = true,
        initialDelayMillis = 250,
        maxDelayMillis = 30_000,
        multiplier = 1.5,
        maxAttempts = null,  // null means infinite
    ),
    tokenRenewalLeadMillis = 60_000,  // renew 60s before exp
    autoAuthenticate = true,
    credentialProvider = {
        Credentials.SignIn(buildJsonObject {
            put("user", JsonPrimitive("root"))
            put("pass", JsonPrimitive("root"))
        })
    },
)
```

## Capability checks

```kotlin
if (client.supports(Feature.LiveQueries)) {
    val sub = db.live(Table("person"))
}
```

`HttpEngine` only advertises `ExportImport` and `SurrealML`. `WebSocketEngine` additionally advertises `LiveQueries`, `Sessions`, `Transactions`, and
`RefreshTokens`. Calling an unsupported method throws `SurrealFeatureNotSupportedException`.

## Spectron

[Spectron](https://surrealdb.com/platform/spectron) is the agent memory and knowledge service. Its client speaks the Spectron end-user HTTP API and is
independent of the SurrealDB RPC engine — so it ships as its own artifact rather than inside the driver, and you depend on it only if you use it.

```kotlin
implementation("com.surrealdb:kotlin-spectron:1.0.0")
```

```kotlin
import com.surrealdb.kotlin.spectron.Spectron

val memory = Spectron(
    contextId = "acme-prod",
    apiKey = "sk-spec-...",
    endpoint = "https://api.spectron.example",
)
memory.remember("I work at Acme as CTO")
val hits = memory.recall("what do I do at Acme", k = 5)
memory.close()
```

All methods are `suspend`. Wrap in `runBlocking { ... }` for synchronous callers.

### Constructor

| Param        | Default          |                                                 |
|--------------|------------------|-------------------------------------------------|
| `contextId`  | required         | Context id, e.g. `"acme-prod"`                  |
| `apiKey`     | required         | Bearer token                                    |
| `endpoint`   | required         | Endpoint, e.g. `"https://api.spectron.example"` |
| `timeout`    | `30.seconds`     | Per-request timeout                             |
| `maxRetries` | `3`              | GET-only retries on 5xx and connect errors      |
| `httpClient` | platform default | Inject your own Ktor `HttpClient` for tests     |
| `json`       | lenient          | `kotlinx.serialization` Json instance           |

`apiKey` and `endpoint` are mutable and take effect on the next request.

The client exposes top-level verbs (`remember`, `rememberMany`, `recall`, `forget`, `chat`, `consolidate`, `audit`, `reflect`, `elaborate`, `inspect`,
`queryContext`, `state`, `profile`, `whoami`, `health`) plus the namespaced surface: `documents`, `sessions`, `entities`, `lifecycle`, `traces`,
`principals`, `scopes`, and `keys`. This mirrors the method placement of the [surrealdb.py](https://github.com/surrealdb/surrealdb.py) Spectron
client.

Scopes are slash-path strings (`"team/eng"`). A scope selector is a DNF (disjunctive-normal-form) value of type `List<List<String>>`: an OR of
clauses, where each clause is an AND of scope paths. A reader matches if they cover **all** the paths in **any one** clause. For example
`listOf(listOf("org/apple"), listOf("org/beta", "region/eu"))` means `org/apple` OR (`org/beta` AND `region/eu`). An empty list targets the caller's
default region.

Two helpers build the shape ergonomically. `scopeSet(...)` makes a single AND-clause from paths, a map, or `(key, value)` pairs (the common case,
where a record is filed under one combination). `scopeSets(...)` joins several clauses with OR, for co-ownership. Both normalise paths, de-duplicate,
preserve order, and drop empty clauses. `scopePaths(...)` is the building block returning a single clause's `List<String>`.

```kotlin
import com.surrealdb.kotlin.spectron.scopeSet
import com.surrealdb.kotlin.spectron.scopeSets

scopeSet(mapOf("org" to "acme"))                      // [["org/acme"]]
scopeSet(listOf("org/acme", "region/eu"))             // [["org/acme", "region/eu"]]  (AND)
scopeSets(listOf("org/apple"), listOf("org/beta"))    // [["org/apple"], ["org/beta"]]  (OR)
```

A single path is the same under either operator, so `scopeSet(listOf("team/eng"))` is just `[["team/eng"]]`. Note the shape changed: a flat
`List<String>` of several paths was previously an AND; the equivalent is now a single nested clause via `scopeSet(...)`.

Every call accepts an optional `onBehalfOf` argument. When set, the request carries the `X-Spectron-On-Behalf-Of` header so a privileged caller can
act as another principal:

```kotlin
memory.recall("open incidents", onBehalfOf = "alpha-bot")
memory.documents.list(status = "ready", onBehalfOf = "alpha-bot")
```

### Memory verbs

```kotlin
import com.surrealdb.kotlin.spectron.model.InferMode
import com.surrealdb.kotlin.spectron.model.Triple
import com.surrealdb.kotlin.spectron.model.TripleEntity

// Free-form fact, extracted server-side.
memory.remember("Christian was promoted to CTO", infer = InferMode.FULL)

// Caller-supplied triples, no LLM.
memory.remember(
    triples = listOf(
        Triple(entity = TripleEntity("christian", "Person"), key = "role", value = "CTO"),
    ),
    infer = InferMode.TRIPLES,
)

// Retrieval over facts and document passages.
val result = memory.recall("What role does Christian have?", k = 10, mode = "hybrid")
result.hits.forEach { println("${it.source} ${it.score} ${it.text}") }

memory.queryContext("brief on tobie", k = 10)
memory.state()
memory.profile()
memory.reflect("patterns in customer complaints this month?", persist = true)
memory.forget("anything about my old job", purge = false)
```

`chat` runs a server-driven turn that retrieves, generates, and persists memory updates in one call:

```kotlin
val reply = memory.chat("What do you know about me?", sessionId = session.id)
println(reply.reply)
println(reply.memoryUpdates?.entities)
```

### Documents

```kotlin
val doc = memory.documents.upload(
    file = bytes,
    filename = "returns.pdf",
    contentType = "application/pdf",
    title = "Returns Policy",
    source = "support-portal",
    scopes = scopeSet(listOf("team/support")),
)

memory.documents.get(doc.id)
memory.documents.chunks(doc.id, page = 0, pageSize = 50)
memory.documents.list(status = "ready", mimeType = "application/pdf")
memory.documents.fetchRaw(doc.id)
memory.documents.reprocess(doc.id)        // re-run the ingestion pipeline
memory.documents.recomputeLinks()
memory.documents.delete(doc.id)
```

Uploads accept a `ByteArray`. On JVM and Android, read a file with `file.readBytes()`. On iOS, use `NSData.bytes` via the appropriate interop.

#### Query

```kotlin
import com.surrealdb.kotlin.spectron.model.GraphEdgeKind
import com.surrealdb.kotlin.spectron.model.QueryMode
import com.surrealdb.kotlin.spectron.model.QueryFilter

val hits = memory.documents.query(
    "what is the return window for unopened items?",
    mode = QueryMode.HYBRID_GRAPH,
    k = 10,
    threshold = 0.5,
    vectorWeight = 0.5,
    rrfK = 60.0,
    graphAlpha = 0.3,
    graphEdges = listOf(GraphEdgeKind.KNOWLEDGE_HAS_KEYWORD, GraphEdgeKind.DOCUMENT_LINK),
    graphDepth = 2,
    expandGraph = true,
    filter = QueryFilter(mimeType = listOf("application/pdf")),
)
```

#### Keywords

```kotlin
memory.documents.keywords.list(minDocumentCount = 2, sort = "-documentCount", q = "return")
memory.documents.keywords.search("refund policies", k = 10, threshold = 0.6)
memory.documents.keywords.get("return-window")
memory.documents.keywords.forDocument(doc.id)
```

### Sessions

`create` returns a `SpectronSession` handle with `chat`, `remember`, `context`, `turns`, and `close`:

```kotlin
val session = memory.sessions.create(scopes = scopeSet(listOf("user/tobie")))

// Server-driven turn scoped to the session.
val reply = session.chat("What do you know about me?")

// Or drive the turns yourself.
session.remember("I just got promoted to CTO", role = TurnRole.USER)
val ctx = session.context("What is Tobie's role?")
val answer = myLlm.chat(system = ctx.context, user = userMessage)
session.remember(answer, role = TurnRole.ASSISTANT)
session.turns(limit = 50)
session.close()
```

The same operations are available as flat namespace calls keyed by session id, matching the Python client:

```kotlin
memory.sessions.context("sess-1", "What is Tobie's role?")
memory.sessions.turns("sess-1", limit = 50, offset = 0)
memory.sessions.delete("sess-1")
```

### Entities, lifecycle, traces

```kotlin
memory.entities.list(type = "Person")
memory.entities.get("Person", "christian_battaglia")
memory.entities.history("Person", "christian_battaglia", key = "role")
memory.entities.delete("Person", "christian_battaglia")

memory.lifecycle.expire()
memory.lifecycle.decay()
memory.lifecycle.fsck(check = "contradictions")

memory.traces.list(limit = 50)
memory.traces.get("decision_trace:abc123")
memory.traces.stats()
```

### Maintenance and introspection

```kotlin
memory.consolidate(dryRun = true)
memory.elaborate(entityRef = "Person/christian", sweep = false)
memory.inspect(ref = "entity:Person/christian", asOf = "2026-01-01T00:00:00Z")
memory.audit(principal = "alpha-bot", limit = 100)

memory.whoami()                 // caller identity and resolved grants
memory.health()                 // liveness probe, not context-scoped
```

### Governance

```kotlin
memory.principals.list()
memory.principals.effective("alpha-bot", path = "org/apple/")
memory.principals.grant("alpha-bot", path = "org/apple/*", verbs = listOf("read", "write"))
memory.principals.revoke("alpha-bot", path = "org/apple/*", verbs = listOf("write"))

memory.scopes.list()
memory.scopes.register("org/apple/product/ipad/", displayName = "iPad")
memory.scopes.forget("org/apple/product/ipad/")
memory.scopes.delete("org/apple/product/ipad/")

// Self-service API keys. The full secret is returned only once, on create or rotate.
val minted = memory.keys.create(name = "ci", ttlSeconds = 3600)
memory.keys.list()
memory.keys.rotate("ci", ttlSeconds = 7200)
memory.keys.delete("ci")
```

Audit lives at the top level as `memory.audit(...)` (see the previous section), mirroring the Python client.

### Errors

```kotlin
try {
    memory.documents.get("doc:missing")
} catch (e: SpectronNotFoundException) {
    println("${e.status}: ${e.title}")
} catch (e: SpectronRateLimitException) {
    println("retry after ${e.retryAfter}")
}
```

| Exception                     | HTTP                               |
|-------------------------------|------------------------------------|
| `SpectronException`           | sealed base                        |
| `SpectronAuthException`       | 401                                |
| `SpectronScopeException`      | 403                                |
| `SpectronNotFoundException`   | 404                                |
| `SpectronValidationException` | 400, 422                           |
| `SpectronRateLimitException`  | 429 (with `retryAfter: Duration?`) |
| `SpectronServerException`     | 5xx                                |
| `SpectronTransportException`  | connect or parse failure           |

Each carries `status`, `title`, `detail`, `typeUri`, `instance`, and `extensions: Map<String, JsonElement>`. The Spectron API returns a
`{ "message": "..." }` error envelope, surfaced as `title`.

### Retries and scope

- GETs retry on connection errors and 5xx with 250 ms, 500 ms, and 1 s backoff, capped to `maxRetries` (default 3). Writes never retry.
- Scope selectors are sent as a DNF `List<List<String>>` (an OR of AND-clauses) of hierarchical `key/value` paths, matching the Spectron scope model.
  The read `lens` takes the same shape.

## Tests

Unit tests:

```bash
./gradlew jvmTest
```

Integration tests against a real SurrealDB:

```bash
docker run -d --name surrealdb -p 8000:8000 surrealdb/surrealdb:latest \
    start --user root --pass root memory

SURREAL_RUN_INTEGRATION=true \
SURREAL_JVM_ENDPOINT=http://127.0.0.1:8000 \
./gradlew jvmTest
```

The integration suite covers the full RPC flow, multi-session isolation, the transaction DSL, and WebSocket live queries.

Run a single test class or method:

```bash
./gradlew jvmTest --tests "com.surrealdb.kotlin.api.RpcMethodsTest"
./gradlew jvmTest --tests "*ErrorMapping*"
```

Mobile integration tests are opt-in and expect a reachable SurrealDB endpoint:

- Android default endpoint: `http://10.0.2.2:8000`
- iOS default endpoint: `http://127.0.0.1:8000`

## Notes

- Embedded mode is intentionally not included in this release.
- The wire codec is JSON-only. CBOR and flatbuffers can be added behind the codec layer in future versions without breaking the public API.
- Buffered call replay after a WebSocket reconnect can produce duplicate side effects if the original send succeeded but the response was lost during
  the disconnect. This trade-off matches the surrealdb.js behaviour.
