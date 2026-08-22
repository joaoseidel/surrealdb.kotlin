# Changelog

## Unreleased

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
