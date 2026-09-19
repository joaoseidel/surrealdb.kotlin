package com.surrealdb.kotlin.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The payload of a JWT as a JSON object, or null if the token cannot be
 * parsed. The signature is not checked; the server did that.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun parseJwtPayload(token: String): JsonObject? {
    val parts = token.split('.')
    if (parts.size < 2) return null
    val payload = parts[1]
    return runCatching {
        val standard = payload.replace('-', '+').replace('_', '/')
        val padded =
            when (standard.length % 4) {
                0 -> standard
                2 -> "$standard=="
                3 -> "$standard="
                else -> standard
            }
        val decoded = Base64.decode(padded).decodeToString()
        Json.parseToJsonElement(decoded).jsonObject
    }.getOrNull()
}

/**
 * Returns the `exp` claim of a JWT in epoch milliseconds, or null if the token
 * cannot be parsed or has no expiry.
 */
internal fun parseJwtExpiryMillis(token: String): Long? =
    (parseJwtPayload(token)?.get("exp") as? JsonPrimitive)
        ?.longOrNull
        ?.let { it * 1000 }

/**
 * The namespace and database a SurrealDB token was issued for. SurrealDB
 * writes them as `NS` and `DB`; a token minted elsewhere for an access method
 * of type JWT may spell them `ns` and `db`. Either half is null when the
 * token does not carry it, as a root token carries neither.
 */
internal fun jwtNamespaceAndDatabase(token: String): Pair<String?, String?> {
    val payload = parseJwtPayload(token) ?: return null to null

    fun claim(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> (payload[name] as? JsonPrimitive)?.takeIf { it.isString }?.content }
    return claim("NS", "ns") to claim("DB", "db")
}
