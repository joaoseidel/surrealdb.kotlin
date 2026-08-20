package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.RecordId
import com.surrealdb.kotlin.api.data.Table
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class Postal(
    val city: String,
    @SerialName("postal_code") val zip: String,
)

internal object People : Table("person") {
    val id = recordId()
    val name by field<String>()
    val age by field<Int>()
    val email by field<String>()
    val active by field<Boolean>()
    val tags by field<List<String>>()
    val firstName = field<String>("first_name")
    val address = field<Postal>("address")
    val city = field<String>("address.city")
}

internal object Posts : Table("post") {
    val author by field<String>()
    val comments by field<List<String>>()
}

internal object Notes : Table("note") {
    val body by field<String>()
    val author = field<RecordId>("author")
}
