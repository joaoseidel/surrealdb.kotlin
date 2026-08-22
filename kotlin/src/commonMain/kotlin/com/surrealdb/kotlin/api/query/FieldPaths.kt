package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Field
import com.surrealdb.kotlin.api.data.Projection

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
