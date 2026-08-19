package com.surrealdb.kotlin.api

import kotlinx.serialization.json.JsonObject

public sealed interface Credentials {
    public data class SignIn(
        public val params: JsonObject,
    ) : Credentials

    public data class Token(
        public val token: String,
    ) : Credentials
}
