package com.surrealdb.kotlin.core.api.data

import kotlinx.serialization.json.JsonElement

public interface LiveQueryTarget {
    public val liveQueryTableName: String
}

public interface LiveQueryFilter {
    public val liveQuerySurql: String

    public val liveQueryBindings: Map<String, JsonElement>
}
