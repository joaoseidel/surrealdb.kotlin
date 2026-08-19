package com.surrealdb.kotlin.api.live

import kotlinx.coroutines.flow.Flow

public class LiveQuerySubscription internal constructor(
    public val id: String,
    public val events: Flow<LiveNotification>,
    private val cancelBlock: suspend () -> Unit,
) {
    public suspend fun cancel(): Unit = cancelBlock()
}
