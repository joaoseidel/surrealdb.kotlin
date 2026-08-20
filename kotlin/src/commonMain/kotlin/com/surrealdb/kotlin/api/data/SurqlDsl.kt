package com.surrealdb.kotlin.api.data

/**
 * Scopes the SurrealQL DSL receivers, so a name from an enclosing block does
 * not resolve inside a nested one.
 */
@DslMarker
public annotation class SurqlDsl
