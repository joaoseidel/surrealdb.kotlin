package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Table
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class Postal(
    val city: String,
    @SerialName("postal_code") val zip: String,
)

@Serializable
internal data class Human(
    val name: String,
    val age: Int,
    val email: String,
    val active: Boolean,
    val tags: List<String>,
    val address: Postal,
    val meta: JsonElement,
    @SerialName("first_name") val firstName: String,
)

@Serializable
internal data class Article(
    val title: String,
    val author: String,
    val comments: List<String>,
)

internal object People : Table<Human>("person", Human.serializer()) {
    val id = recordId()
    val name by field<String>()
    val age by field<Int>()
    val email by field<String>()
    val active by field<Boolean>()
    val tags by field<List<String>>()
    val firstName = field<String>("first_name")
    val address by nested<Postal>()
}

internal object Posts : Table<Article>("post", Article.serializer()) {
    val author by field<String>()
    val comments by field<List<String>>()
}
