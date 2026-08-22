package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.Projection
import com.surrealdb.kotlin.query.api.data.Field

internal val Projection.holdsAnIndex: Boolean get() = '[' in path.value

internal val Projection.namesEveryElement: Boolean get() = "[*]" in path.value

internal fun Field<*>.segments(): List<String> =
    path.value
        .replace('[', '.')
        .replace("]", "")
        .split('.')

internal fun Field<*>.endsAtAnIndex(): Boolean = segments().last().isAnIndex()

internal fun Field<*>.reachesThroughAnIndex(): Boolean = segments().dropLast(1).any { it.isAnIndex() }

private fun String.isAnIndex(): Boolean = isNotEmpty() && all { it.isDigit() }
