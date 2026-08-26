# Changelog

## Unreleased

- `signin` and `signup` answer with `AuthTokens` rather than the raw response, reading the bare-JWT
  and the `access` / `token` / `jwt` object shapes the server uses. Null where the access method
  issues no token; a refusal is still an error.
- `EdgeTable` declares the `in` and `out` a relation table carries, so a filter on either end reads
  the same whichever edge it is written against.
- `relate(...).content { }` names the edge's fields through the relation's own declaration, as the
  other write builders already did.
- `containsIgnoringCase` folds case on both sides and reads an absent field as empty, which is what
  a search box wants and what `matches` and `matchesFullText` each do not do.
- `SurqlTemplate.record` writes a record id inside a raw fragment with both halves bound.
- `RecordId.walk(direction, edge, table)` takes the direction as a value, for a walk decided at
  runtime.
- `select` and `count` take a graph traversal as their target, and `where { }` compares against one,
  so reading across an edge no longer means leaving the builder. A traversal names its destination
  table, which is what keeps an edge pointing elsewhere out of the answer, and it starts at a record
  rather than a table, because SurrealDB answers a walk from a whole table with nothing at all.
- A traversal projected beside the record arrives under a declared field, `aliasedAs`, so the row
  reads it back through the same declaration that asked for it. Its type has to agree with the
  traversal's, which makes an unindexed walk assigned to a single-record field a compile error.
- `orderBy` sorts a select, with `COLLATE` and `NUMERIC` where the comparison is not the default one.
  A key that the projection left out is refused where the query is written, because SurrealDB sorts
  only what it selected and refuses the statement outright.
- `count` answers how many records a target holds, under the same conditions a select takes, and
  answers zero for a target that matched nothing.
- An array field is assignable as a set with `include` and `exclude`, so a value added twice is held
  once. SurrealQL's `+=` appends whatever it is handed.
- `matchesFullText` writes SurrealDB's `@@`, which reads the `FULLTEXT` index defined on the field.
  `matches` is `string::matches`, a regular expression over every record, and reaching for it on an
  indexed field is a table scan that looks like a search.
- A datetime assigned in a `set { }` block now renders as `type::datetime(...)`, the same spelling a
  condition already compared one through. Bound as a value it reached the server as text, which a
  `datetime` field refuses.
- A condition comparing against a collection of records now writes each of them out rather than
  refusing to bind the collection as JSON.
- Core and query packages now include their module prefix while retaining the existing `api` and `runtime` hierarchy.
- Conditions now accept fields from the table declaration instead of a serializable record type, so
  field validity is checked where the query is written.
- `and` and `or` no longer mix without `all`, `any`, or another explicit group, which prevents Kotlin
  call precedence from changing the intended SurrealQL condition.
- `relate` rejects `RecordIdRange` endpoints because SurrealDB does not parse ranges in a relation
  position.
- Patch builders now take `ReturnMode`, matching the other write builders and replacing implicit
  response-shape assumptions.
- `live` now takes a `Table` and a `LiveMode`, so identifiers and notification payload shape are
  explicit.
- The build now requires AGP 9, Kotlin 2.4.10, stable Kotest 6.2.4, and JDK 25 to keep supported tool
  versions aligned.
- Ktor was upgraded to 3.5.2, which removes compatibility with Ktor 2 consumers.
- A record selected through a declared table is now represented by `TableRecord<S>`, preserving the
  schema required by typed field builders.
- `only()` is now an explicit query modifier instead of being inferred from the target, because table
  and record result shapes do not consistently imply SurrealQL `ONLY`.
- Tables are now declared once as `object Users : Table("user")`; separate wire record declarations
  were removed to prevent schema drift.
- The SurQL DSL marker now prevents outer table fields from resolving inside `raw`, making raw query
  boundaries visible at the call site.
- Query builders now name the decoded type only at `decodeAs<T>()` and return lists by default, so a
  target no longer guesses the response cardinality.
- Results are read through `Row` and the fields declared on a `Table`, replacing duplicate DTO-based
  field access.
- Nested object fields now use `Nested` declarations, so a path is named once at any depth.
- Whole nested-object projection now uses the `Nested` declaration itself, keeping projection paths
  consistent with row reads.
- Literal `surql` overloads were consolidated around `BoundQuery`, removing duplicate raw-query
  construction paths.
- Typed merge blocks now fold nested field paths into the object payload SurrealDB expects.
- Typed patch blocks now build JSON Patch operations from declared fields, preventing unchecked path
  strings in the primary API.
- Authentication now uses the sealed `Credentials` hierarchy, so the credential type selects a root,
  namespace, database, or record access level instead of an error-prone set of keys.
- Dynamic raw SurrealQL now uses `surqlTemplate { bind(value) }`, making caller-provided values bound
  by construction.
- `Field.path` now exposes a checked `FieldPath` throughout rendering, so an invalid path cannot reach
  a final SurrealQL splice as an unchecked string.
- `Field` is final because callers could not construct a valid external subclass through its internal
  constructor.
- Target values can no longer be passed through the public JSON encoder because JSON cannot preserve
  their SurrealQL target type.
- Identifier escaping now uses one renderer for record ids, relation tables, and projection aliases,
  including identifiers that require backticks.
- `SurqlBuilder`, public literal `surql` overloads, and named binding were removed; `surqlTemplate` is
  the value-safe raw query entry point and `BoundQuery` covers programmatic composition.
- `Session.auth()` was renamed to `whoami()` to name the information it returns rather than the action
  used to obtain it.
- `Surreal.supports(feature)` was removed because `feature in client.features` provides the same
  capability without a duplicate method.
