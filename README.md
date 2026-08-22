# surrealdb.kotlin

A coroutine-first Kotlin Multiplatform driver for SurrealDB on Android, JVM, and iOS.

The driver selects HTTP or WebSocket transport from the URL. A `Surreal` owns the connection, while
each `Session` owns its namespace, database, authentication, and variables. Typed query builders
compile to SurrealQL with bound values and send it through the `query` RPC.

## Install

```kotlin
dependencies {
    implementation("com.surrealdb:kotlin:1.0.0")
}
```

The artifact is not published yet. For a local build, run `./gradlew publishToMavenLocal` and add
`mavenLocal()` to the consuming project. Kotlin Multiplatform module metadata selects the JVM,
Android, or matching iOS variant.

## Connect and open a session

```kotlin
val client = Surreal(Surreal.Config(url = "ws://127.0.0.1:8000"))

try {
    val db = client.session()
    db.signin(Credentials.RootUser("root", "root"))
    db.use(Namespace("main"), Database("main"))

    println(db.version())
} finally {
    client.close()
}
```

Construction connects eagerly by default. Set `autoConnect = false` and call `connect()` when the
application needs to control that point. Sessions share the transport but not mutable session state.
Call `closeSession(session)` when a long-lived client no longer needs one session.

## Declare tables and fields

Declare a table once and use the same object for queries, assignments, projections, and row reads:

```kotlin
object People : Table("person") {
    val id = recordId()
    val name by field<String>()
    val age by field<Int>()
    val displayName = field<String>("display_name")

    object Address : Nested("address") {
        val city by field<String>()
        val postcode = field<String>("postal_code")
    }

    val address = nested(Address)
}
```

`val name by field<String>()` is the primary declaration form. It takes the Kotlin property name as
the server field name. Use `field<String>("server_name")` only when the server name differs from the
Kotlin property.

`Nested` keeps a checked path through the query builder. Its leaves work like ordinary fields:

```kotlin
db.select(People).where { address.city eq "London" }
row[People.address.city]
```

## Read records

Builders bind values and return rows through `await()`:

```kotlin
val adults: List<Row> =
    db.select(People)
        .where { age greaterEq 18 }
        .limit(50)
        .await()

val firstName: String = adults.first()[People.name]
val firstId: RecordId = adults.first()[People.id]
```

A table target returns any matching records. `People["ada"]` retains the table declaration while
targeting one record, so typed fields remain available:

```kotlin
val ada: Row? = db.select(People["ada"]).awaitSingleOrNull()
```

Use `decodeAs<T>()` when the application has a serializable domain type:

```kotlin
@Serializable
data class Person(val id: RecordId, val name: String, val age: Int)

val people: List<Person> =
    db.select(People)
        .where { age greaterEq 18 }
        .decodeAs<Person>()
        .await()
```

`await()` and `awaitSingleOrNull()` read records as `Row`. Placing `decodeAs<T>()` before the terminal
decodes the same response as the caller's type.

## Project fields

`fields` selects declared fields or a whole nested group. The returned `Row` uses the same
declarations:

```kotlin
val rows =
    db.select(People)
        .fields(People.name, People.address)
        .await()

for (row in rows) {
    println("${row[People.name]} lives in ${row[People.address.city]}")
}
```

Use `value` when the server should return values instead of records, then decode that result:

```kotlin
val names: List<String> =
    db.select(People)
        .value(People.name)
        .decodeAs<String>()
        .await()
```

Indexed fields are aliased back to their declared path so separate projections do not overwrite one
another. A wildcard field such as `field<List<String?>>("authors[*].name")` reads every matching
element.

## Write records

Use the same fields for typed assignments:

```kotlin
db.update(People["ada"])
    .set {
        it[name] = "Ada Lovelace"
        it[age] = 36
        it[address.city] = "London"
    }
    .await()
```

`create`, `upsert`, and `update` support `set` and JSON `content`. `merge` changes only the fields in
its payload, and `patch` builds JSON Patch operations:

```kotlin
db.merge(People["ada"]) { it[displayName] = "Ada" }.await()
db.patch(People["ada"]) { it.replace(age, 37) }.await()
db.delete(People["ada"]).await()
```

The typed merge and patch blocks protect field paths. Their `JsonElement` overloads remain available
for payloads assembled elsewhere and for operations the typed DSL does not model.

Every write builder accepts a `ReturnMode`. `ReturnMode.Diff` returns JSON Patch operations rather
than rows, so decode that response instead of calling the row terminal.

## Credentials

Credential types make the intended SurrealDB authentication level explicit:

```kotlin
db.signin(Credentials.RootUser("root", "root"))

db.signin(
    Credentials.NamespaceUser(
        namespace = Namespace("main"),
        user = "operator",
        pass = "secret",
    ),
)

db.signin(
    Credentials.DatabaseUser(
        namespace = Namespace("main"),
        database = Database("main"),
        user = "reader",
        pass = "secret",
    ),
)
```

`Credentials.RecordUser` supports record access methods and is valid for both `signup` and `signin`.
`Credentials.Raw` is the unchecked escape for access methods this version does not model.
`authenticate(token)` accepts an existing JWT. `whoami()` returns the current authentication record,
and `invalidate()` clears it.

## Dynamic raw SurrealQL

Use typed builders whenever a table, field, relation, or other identifier varies. SurrealDB cannot
bind identifiers as parameters, so the builders validate and escape them in their grammatical
positions.

Use `surqlTemplate` for caller-provided values in raw SurrealQL:

```kotlin
val minimumAge = 18
val query = surqlTemplate {
    "SELECT * FROM person WHERE age >= ${bind(minimumAge)}"
}

val envelope: JsonElement = db.query(query)
```

Never quote a `bind(...)` result. It is already a generated SurrealQL parameter, and SurrealDB does
not substitute parameters inside string literals. `BoundQuery` remains public for programmatic query
composition when a single template block is not enough.

## Live queries

Live queries require a `ws://` or `wss://` connection. The direct table subscription exposes raw
notifications and an explicit lifetime:

```kotlin
val subscription = db.live(People)

try {
    subscription.events.collect { notification ->
        println(notification)
    }
} finally {
    subscription.cancel()
}
```

Use `liveEvents<T>` when a filtered `LIVE SELECT` and decoded event stream are more useful:

```kotlin
db.liveEvents<Person>("LIVE SELECT * FROM person WHERE age >= 18")
    .collect { event -> println(event) }
```

The `live` subscription is an explicitly cancellable server handle. `liveEvents` is a cold `Flow`
whose collection owns the query lifetime.

## Transactions

The scoped form commits on success and cancels on failure:

```kotlin
db.transaction {
    update(People["ada"]).set { it[age] = 37 }.await()
    update(People["grace"]).set { it[age] = 38 }.await()
}
```

Use `beginTransaction()` only when the caller must own the manual `commit()` or `cancel()` lifecycle.
Transactions require WebSocket transport.

## Transport and language constraints

- `Duration` and `Decimal` values are unsupported over the current JSON transport. JSON has no
  native representation that preserves either SurrealDB type, so binding them would silently change
  their meaning.
- A bound string that looks like a record id can be reinterpreted or truncated by SurrealDB while it
  parses JSON query parameters. This applies to every clause that carries a bound value. CBOR is the
  future transport fix and is not part of this work.
- There is no blocking facade for Java callers. Java code must bridge the suspend API with its own
  coroutine or asynchronous adapter.
- WebSocket replay after reconnect can duplicate a side effect if the server processed the request
  but the response was lost.
- Embedded mode is not included.

## Executable quickstart

[`samples/jvm-quickstart`](samples/jvm-quickstart) exercises the primary connection, table, nested
field, read, write, projection, decoding, raw query, and live-query spellings as a separate consumer
module. CI compiles it with:

```bash
./gradlew :samples:jvm-quickstart:compileKotlin
```

With SurrealDB listening locally, run it with:

```bash
SURREAL_ENDPOINT=ws://127.0.0.1:8000 ./gradlew :samples:jvm-quickstart:run
```

## Spectron

The optional `com.surrealdb:kotlin-spectron:1.0.0` artifact is an HTTP client for Spectron memory and
knowledge services. It is separate from the SurrealDB driver and uses the same coroutine-first,
Kotlin Multiplatform conventions.

## Verify the project

```bash
./gradlew jvmTest
./gradlew :kotlin:iosSimulatorArm64Test
./gradlew ktlintCheck
./gradlew verifyPublicationCoordinates verifyPublicationJavadoc
```

Real-server JVM tests are opt-in:

```bash
docker run -d --name surrealdb -p 8000:8000 surrealdb/surrealdb:v3.2.4 \
    start --user root --pass root memory

SURREAL_RUN_INTEGRATION=true \
SURREAL_JVM_ENDPOINT=ws://127.0.0.1:8000 \
./gradlew :kotlin:jvmTest --rerun-tasks
```
