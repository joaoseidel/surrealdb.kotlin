package com.surrealdb.kotlin.runtime.engine

import com.surrealdb.kotlin.api.ConnectionEvent
import com.surrealdb.kotlin.api.Feature
import com.surrealdb.kotlin.api.error.ErrorKind
import com.surrealdb.kotlin.api.error.SurrealAlreadyExistsException
import com.surrealdb.kotlin.api.error.SurrealAuthenticationException
import com.surrealdb.kotlin.api.error.SurrealNotFoundException
import com.surrealdb.kotlin.api.error.SurrealQueryException
import com.surrealdb.kotlin.api.error.SurrealRpcException
import com.surrealdb.kotlin.api.live.LiveNotification
import com.surrealdb.kotlin.api.live.LiveQueryFailure
import com.surrealdb.kotlin.runtime.RpcError
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

internal data class SessionSnapshot(
    val token: String?,
    val namespace: String?,
    val database: String?,
    val variables: Map<String, JsonElement> = emptyMap(),
)

internal interface Engine :
    Protocol,
    AutoCloseable {
    val features: Set<Feature>
    val events: SharedFlow<ConnectionEvent>
    val liveNotifications: SharedFlow<LiveNotification>
    val liveFailures: SharedFlow<LiveQueryFailure>
    val activeLiveQueries: StateFlow<Set<String>>

    suspend fun trackLiveQuery(
        liveQueryId: String,
        source: LiveQuerySource,
    )

    suspend fun start()
}

/**
 * Maps a wire-level [RpcError] to a typed [SurrealRpcException].
 *
 * The server attaches a structured `kind` (and, for most kinds, nested
 * `details`) to every error it returns — see `surrealdb_types::Error` /
 * `ErrorDetails` server-side. We parse that structured data via
 * [ErrorKind] and use it to pick the most specific exception subtype,
 * rather than guessing from the free-text `message` (JSON-RPC code -32000 is
 * the generic SurrealDB server error used for *every* RPC failure, so it was
 * never a useful signal either).
 *
 * A missing `kind` (e.g. a very old server) falls back to
 * [ErrorKind.Internal], so unrecognised errors still surface as a
 * plain [SurrealRpcException] rather than failing to parse.
 */
internal fun mapRpcError(error: RpcError): SurrealRpcException {
    val kind = ErrorKind.parse(error.kind, error.details)
    return when {
        kind is ErrorKind.NotAllowed && kind.detail is ErrorKind.NotAllowed.Detail.Auth -> {
            SurrealAuthenticationException(code = error.code, message = error.message, data = error.data, kind = kind)
        }

        kind is ErrorKind.NotFound -> {
            SurrealNotFoundException(code = error.code, message = error.message, data = error.data, kind = kind)
        }

        kind is ErrorKind.AlreadyExists -> {
            SurrealAlreadyExistsException(code = error.code, message = error.message, data = error.data, kind = kind)
        }

        kind is ErrorKind.Query -> {
            SurrealQueryException(code = error.code, message = error.message, data = error.data, kind = kind)
        }

        else -> {
            SurrealRpcException(code = error.code, message = error.message, data = error.data, kind = kind)
        }
    }
}
