package com.surrealdb.kotlin.core.api.error

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Structured, typed representation of the `kind` (and, where present, nested
 * `details`) fields the SurrealDB server attaches to every RPC error on the
 * wire.
 *
 * This mirrors the server's `surrealdb_types::ErrorDetails` enum
 * (`types/src/error.rs`): every error family the server can report has a
 * matching subtype here, so callers can branch on error semantics; e.g. "was
 * the access token expired?", "was this a transaction conflict?"; instead of
 * pattern-matching the free-text [SurrealRpcException.message], which is
 * meant for humans and is not a stable contract.
 *
 * `kind` values this SDK version does not recognise (e.g. because the
 * connected server is newer than the SDK) fall back to [Unknown]; a missing
 * `kind` (e.g. a very old server) falls back to [Internal]. Both mirror the
 * server's own forward-compatible fallback to `Internal` for unrecognised
 * kinds.
 */
public sealed class ErrorKind {
    /** Parse error, invalid request, or invalid/malformed parameters. */
    public data class Validation(
        public val detail: Detail? = null,
    ) : ErrorKind() {
        public sealed class Detail {
            public data object Parse : Detail()

            public data object InvalidRequest : Detail()

            public data object InvalidParams : Detail()

            public data object NamespaceEmpty : Detail()

            public data object DatabaseEmpty : Detail()

            public data class InvalidParameter(
                public val name: String,
            ) : Detail()

            public data class InvalidContent(
                public val value: String,
            ) : Detail()

            public data class InvalidMerge(
                public val value: String,
            ) : Detail()
        }
    }

    /** A requested feature or configuration is not supported by this server. */
    public data class Configuration(
        public val detail: Detail? = null,
    ) : ErrorKind() {
        public sealed class Detail {
            public data object LiveQueryNotSupported : Detail()

            public data object BadLiveQueryConfig : Detail()

            public data object BadGraphqlConfig : Detail()
        }

        /** True if live queries are not supported by this server configuration. */
        public val isLiveQueryNotSupported: Boolean get() = detail is Detail.LiveQueryNotSupported
    }

    /** A user-thrown error, e.g. `THROW "reason"` in SurrealQL. */
    public data object Thrown : ErrorKind()

    /** A query failed to execute (as opposed to succeeding with an empty result). */
    public data class Query(
        public val detail: Detail? = null,
    ) : ErrorKind() {
        public sealed class Detail {
            public data object NotExecuted : Detail()

            public data class TimedOut(
                public val seconds: Long,
                public val nanos: Long,
            ) : Detail()

            public data object Cancelled : Detail()

            public data object TransactionConflict : Detail()
        }

        /** True if a concurrent transaction wrote to the same data; safe to retry. */
        public val isTransactionConflict: Boolean get() = detail is Detail.TransactionConflict

        /** True if the query exceeded its configured timeout. */
        public val isTimedOut: Boolean get() = detail is Detail.TimedOut

        /** True if the statement was skipped, e.g. after a prior failure in the same batch. */
        public val isNotExecuted: Boolean get() = detail is Detail.NotExecuted

        /** True if the query was cancelled before completing. */
        public val isCancelled: Boolean get() = detail is Detail.Cancelled
    }

    /** Serializing or deserializing a value failed. */
    public data class Serialization(
        public val detail: Detail? = null,
    ) : ErrorKind() {
        public sealed class Detail {
            public data object Serialization : Detail()

            public data object Deserialization : Detail()
        }

        /** True if this is a deserialization failure (as opposed to serialization). */
        public val isDeserialization: Boolean get() = detail is Detail.Deserialization
    }

    /** Permission denied, or a method/function/scripting target is blocked. */
    public data class NotAllowed(
        public val detail: Detail? = null,
    ) : ErrorKind() {
        public sealed class Detail {
            public data object Scripting : Detail()

            public data class Auth(
                public val reason: AuthReason,
            ) : Detail()

            public data class Method(
                public val name: String,
            ) : Detail()

            public data class Function(
                public val name: String,
            ) : Detail()

            public data class Target(
                public val name: String,
            ) : Detail()
        }

        /** The specific authentication/authorization failure behind an [Detail.Auth] error. */
        public sealed class AuthReason {
            public data object TokenExpired : AuthReason()

            public data object SessionExpired : AuthReason()

            public data object InvalidAuth : AuthReason()

            public data object UnexpectedAuth : AuthReason()

            public data object MissingUserOrPass : AuthReason()

            public data object NoSigninTarget : AuthReason()

            public data object InvalidPass : AuthReason()

            public data object TokenMakingFailed : AuthReason()

            public data object InvalidSignup : AuthReason()

            public data class InvalidRole(
                public val name: String,
            ) : AuthReason()

            public data class NotPermitted(
                public val actor: String,
                public val action: String,
                public val resource: String,
            ) : AuthReason()

            /** An auth-failure reason this SDK version does not recognise yet. */
            public data class Unrecognized(
                public val rawReason: String,
            ) : AuthReason()
        }

        /** True if this failure was caused by an expired access token; reconnect and retry. */
        public val isTokenExpired: Boolean
            get() = (detail as? Detail.Auth)?.reason is AuthReason.TokenExpired

        /** True if this failure was caused by invalid credentials (not token expiry). */
        public val isInvalidAuth: Boolean
            get() = (detail as? Detail.Auth)?.reason is AuthReason.InvalidAuth

        /** True if the request was rejected because scripting is disabled on this server. */
        public val isScriptingBlocked: Boolean get() = detail is Detail.Scripting
    }

    /** The requested resource does not exist. */
    public data class NotFound(
        public val detail: Detail? = null,
    ) : ErrorKind() {
        public sealed class Detail {
            public data class Method(
                public val name: String,
            ) : Detail()

            public data class Session(
                public val id: String?,
            ) : Detail()

            public data class Table(
                public val name: String,
            ) : Detail()

            public data class Record(
                public val id: String,
            ) : Detail()

            public data class Namespace(
                public val name: String,
            ) : Detail()

            public data class Database(
                public val name: String,
            ) : Detail()

            public data object Transaction : Detail()
        }
    }

    /** The resource being created already exists. */
    public data class AlreadyExists(
        public val detail: Detail? = null,
    ) : ErrorKind() {
        public sealed class Detail {
            public data class Session(
                public val id: String,
            ) : Detail()

            public data class Table(
                public val name: String,
            ) : Detail()

            public data class Record(
                public val id: String,
            ) : Detail()

            public data class Namespace(
                public val name: String,
            ) : Detail()

            public data class Database(
                public val name: String,
            ) : Detail()
        }
    }

    /**
     * Client-side connection state error (uninitialised, already connected,
     * transport failure). Part of the server's shared error taxonomy, but not
     * currently expected over the wire; included for forward compatibility.
     */
    public data class Connection(
        public val detail: Detail? = null,
    ) : ErrorKind() {
        public sealed class Detail {
            public data object Uninitialised : Detail()

            public data object AlreadyConnected : Detail()

            public data object ConnectionFailed : Detail()
        }
    }

    /** Internal or unexpected server error, or an error not covered by the other kinds. */
    public data object Internal : ErrorKind()

    /** Context wrapper attached purely for error chaining (see the server's `cause` field). */
    public data object Context : ErrorKind()

    /** A `kind` string this SDK version does not recognise (forward compatibility). */
    public data class Unknown(
        public val rawKind: String,
    ) : ErrorKind()

    public companion object {
        /**
         * Parses the wire `kind`/`details` pair reported by the server into a
         * typed [ErrorKind]. A missing `kind` falls back to [Internal];
         * an unrecognised `kind` string falls back to [Unknown]; both mirror
         * the server's own forward-compatibility policy.
         */
        internal fun parse(
            kind: String?,
            details: JsonElement?,
        ): ErrorKind {
            if (kind == null) return Internal
            val tag = details.asTag()
            return when (kind) {
                "Validation" -> Validation(tag?.let(::validationDetail))
                "Configuration" -> Configuration(tag?.let(::configurationDetail))
                "Query" -> Query(tag?.let(::queryDetail))
                "Serialization" -> Serialization(tag?.let(::serializationDetail))
                "NotAllowed" -> NotAllowed(tag?.let(::notAllowedDetail))
                "NotFound" -> NotFound(tag?.let(::notFoundDetail))
                "AlreadyExists" -> AlreadyExists(tag?.let(::alreadyExistsDetail))
                "Connection" -> Connection(tag?.let(::connectionDetail))
                "Thrown" -> Thrown
                "Internal" -> Internal
                "Context" -> Context
                else -> Unknown(kind)
            }
        }
    }
}

// The server encodes every nested error detail using the same recursive
// `{ "kind": "...", "details"?: ... }` shape (see `types/src/error.rs`'s
// `#[surreal(tag = "kind", content = "details")]` enums), so a single `Tag`
// extraction helper is shared across all error families below.

private class Tag(
    val kind: String,
    val details: JsonElement?,
)

private fun JsonElement?.asTag(): Tag? {
    val obj = this as? JsonObject ?: return null
    val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
    return Tag(kind, obj["details"])
}

private fun JsonObject?.string(field: String): String? = (this?.get(field) as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.long(field: String): Long? = (this?.get(field) as? JsonPrimitive)?.longOrNull

private fun validationDetail(tag: Tag): ErrorKind.Validation.Detail? {
    val d = tag.details as? JsonObject
    return when (tag.kind) {
        "Parse" -> ErrorKind.Validation.Detail.Parse
        "InvalidRequest" -> ErrorKind.Validation.Detail.InvalidRequest
        "InvalidParams" -> ErrorKind.Validation.Detail.InvalidParams
        "NamespaceEmpty" -> ErrorKind.Validation.Detail.NamespaceEmpty
        "DatabaseEmpty" -> ErrorKind.Validation.Detail.DatabaseEmpty
        "InvalidParameter" -> d.string("name")?.let { ErrorKind.Validation.Detail.InvalidParameter(it) }
        "InvalidContent" -> d.string("value")?.let { ErrorKind.Validation.Detail.InvalidContent(it) }
        "InvalidMerge" -> d.string("value")?.let { ErrorKind.Validation.Detail.InvalidMerge(it) }
        else -> null
    }
}

private fun configurationDetail(tag: Tag): ErrorKind.Configuration.Detail? =
    when (tag.kind) {
        "LiveQueryNotSupported" -> ErrorKind.Configuration.Detail.LiveQueryNotSupported
        "BadLiveQueryConfig" -> ErrorKind.Configuration.Detail.BadLiveQueryConfig
        "BadGraphqlConfig" -> ErrorKind.Configuration.Detail.BadGraphqlConfig
        else -> null
    }

private fun queryDetail(tag: Tag): ErrorKind.Query.Detail? {
    val d = tag.details as? JsonObject
    return when (tag.kind) {
        "NotExecuted" -> {
            ErrorKind.Query.Detail.NotExecuted
        }

        "TimedOut" -> {
            val duration = d?.get("duration") as? JsonObject
            ErrorKind.Query.Detail.TimedOut(
                seconds = duration.long("secs") ?: 0L,
                nanos = duration.long("nanos") ?: 0L,
            )
        }

        "Cancelled" -> {
            ErrorKind.Query.Detail.Cancelled
        }

        "TransactionConflict" -> {
            ErrorKind.Query.Detail.TransactionConflict
        }

        else -> {
            null
        }
    }
}

private fun serializationDetail(tag: Tag): ErrorKind.Serialization.Detail? =
    when (tag.kind) {
        "Serialization" -> ErrorKind.Serialization.Detail.Serialization
        "Deserialization" -> ErrorKind.Serialization.Detail.Deserialization
        else -> null
    }

private fun notAllowedDetail(tag: Tag): ErrorKind.NotAllowed.Detail? {
    val dObj = tag.details as? JsonObject
    return when (tag.kind) {
        "Scripting" -> ErrorKind.NotAllowed.Detail.Scripting
        "Auth" -> tag.details.asTag()?.let { ErrorKind.NotAllowed.Detail.Auth(authReason(it)) }
        "Method" -> dObj.string("name")?.let { ErrorKind.NotAllowed.Detail.Method(it) }
        "Function" -> dObj.string("name")?.let { ErrorKind.NotAllowed.Detail.Function(it) }
        "Target" -> dObj.string("name")?.let { ErrorKind.NotAllowed.Detail.Target(it) }
        else -> null
    }
}

private fun authReason(tag: Tag): ErrorKind.NotAllowed.AuthReason {
    val d = tag.details as? JsonObject
    return when (tag.kind) {
        "TokenExpired" -> {
            ErrorKind.NotAllowed.AuthReason.TokenExpired
        }

        "SessionExpired" -> {
            ErrorKind.NotAllowed.AuthReason.SessionExpired
        }

        "InvalidAuth" -> {
            ErrorKind.NotAllowed.AuthReason.InvalidAuth
        }

        "UnexpectedAuth" -> {
            ErrorKind.NotAllowed.AuthReason.UnexpectedAuth
        }

        "MissingUserOrPass" -> {
            ErrorKind.NotAllowed.AuthReason.MissingUserOrPass
        }

        "NoSigninTarget" -> {
            ErrorKind.NotAllowed.AuthReason.NoSigninTarget
        }

        "InvalidPass" -> {
            ErrorKind.NotAllowed.AuthReason.InvalidPass
        }

        "TokenMakingFailed" -> {
            ErrorKind.NotAllowed.AuthReason.TokenMakingFailed
        }

        "InvalidSignup" -> {
            ErrorKind.NotAllowed.AuthReason.InvalidSignup
        }

        "InvalidRole" -> {
            ErrorKind.NotAllowed.AuthReason.InvalidRole(d.string("name") ?: "")
        }

        "NotAllowed" -> {
            ErrorKind.NotAllowed.AuthReason.NotPermitted(
                actor = d.string("actor") ?: "",
                action = d.string("action") ?: "",
                resource = d.string("resource") ?: "",
            )
        }

        else -> {
            ErrorKind.NotAllowed.AuthReason.Unrecognized(tag.kind)
        }
    }
}

private fun notFoundDetail(tag: Tag): ErrorKind.NotFound.Detail? {
    val d = tag.details as? JsonObject
    return when (tag.kind) {
        "Method" -> d.string("name")?.let { ErrorKind.NotFound.Detail.Method(it) }
        "Session" -> ErrorKind.NotFound.Detail.Session(d.string("id"))
        "Table" -> d.string("name")?.let { ErrorKind.NotFound.Detail.Table(it) }
        "Record" -> d.string("id")?.let { ErrorKind.NotFound.Detail.Record(it) }
        "Namespace" -> d.string("name")?.let { ErrorKind.NotFound.Detail.Namespace(it) }
        "Database" -> d.string("name")?.let { ErrorKind.NotFound.Detail.Database(it) }
        "Transaction" -> ErrorKind.NotFound.Detail.Transaction
        else -> null
    }
}

private fun alreadyExistsDetail(tag: Tag): ErrorKind.AlreadyExists.Detail? {
    val d = tag.details as? JsonObject
    return when (tag.kind) {
        "Session" -> d.string("id")?.let { ErrorKind.AlreadyExists.Detail.Session(it) }
        "Table" -> d.string("name")?.let { ErrorKind.AlreadyExists.Detail.Table(it) }
        "Record" -> d.string("id")?.let { ErrorKind.AlreadyExists.Detail.Record(it) }
        "Namespace" -> d.string("name")?.let { ErrorKind.AlreadyExists.Detail.Namespace(it) }
        "Database" -> d.string("name")?.let { ErrorKind.AlreadyExists.Detail.Database(it) }
        else -> null
    }
}

private fun connectionDetail(tag: Tag): ErrorKind.Connection.Detail? =
    when (tag.kind) {
        "Uninitialised" -> ErrorKind.Connection.Detail.Uninitialised
        "AlreadyConnected" -> ErrorKind.Connection.Detail.AlreadyConnected
        "ConnectionFailed" -> ErrorKind.Connection.Detail.ConnectionFailed
        else -> null
    }
