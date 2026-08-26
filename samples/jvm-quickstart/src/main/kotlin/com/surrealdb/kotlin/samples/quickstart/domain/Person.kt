package com.surrealdb.kotlin.samples.quickstart.domain

import com.surrealdb.kotlin.core.api.data.RecordId

data class Person(
    val id: RecordId,
    val name: String,
    val age: Int,
    val displayName: String,
    val address: Address,
) {
    data class Address(
        val city: String,
    )
}
