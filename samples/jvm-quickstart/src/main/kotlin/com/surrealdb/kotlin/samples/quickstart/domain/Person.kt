package com.surrealdb.kotlin.samples.quickstart.domain

import com.surrealdb.kotlin.core.api.data.RecordId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Person(
    val id: RecordId,
    val name: String,
    val age: Int,
    @SerialName("display_name")
    val displayName: String,
    val address: Address,
) {
    @Serializable
    data class Address(
        val city: String,
    )
}
