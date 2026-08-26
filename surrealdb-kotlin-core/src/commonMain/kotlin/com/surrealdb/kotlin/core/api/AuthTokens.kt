package com.surrealdb.kotlin.core.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * What signing in or up answers with: the token the session now holds, and the
 * one it can renew with where the access method issues one.
 *
 * SurrealDB answers with a bare JWT on some access methods and with an object
 * on others, and the object spells the access token `access`, `token` or `jwt`
 * depending on the version. [Session.signin] reads all of those, so a caller
 * reads one shape rather than four.
 */
public data class AuthTokens(
    public val accessToken: String,
    public val refreshToken: String?,
)

/** Reads a sign-in or sign-up result, or null for a response carrying no token. */
internal fun JsonElement.asAuthTokensOrNull(): AuthTokens? =
    when {
        this is JsonPrimitive && isString -> {
            AuthTokens(content, null)
        }

        this is JsonObject -> {
            val access = (this["access"] ?: this["token"] ?: this["jwt"])?.jsonPrimitive?.content
            access?.let { AuthTokens(it, this["refresh"]?.jsonPrimitive?.content) }
        }

        else -> {
            null
        }
    }
