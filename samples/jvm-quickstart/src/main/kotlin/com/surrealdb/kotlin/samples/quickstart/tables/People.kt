package com.surrealdb.kotlin.samples.quickstart.tables

import com.surrealdb.kotlin.api.data.Nested
import com.surrealdb.kotlin.api.data.Row
import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.samples.quickstart.domain.Person
import com.surrealdb.kotlin.samples.quickstart.tables.People.address
import com.surrealdb.kotlin.samples.quickstart.tables.People.age
import com.surrealdb.kotlin.samples.quickstart.tables.People.displayName
import com.surrealdb.kotlin.samples.quickstart.tables.People.id
import com.surrealdb.kotlin.samples.quickstart.tables.People.name

object People : Table("person") {
    val id = recordId()
    val name by field<String>()
    val age by field<Int>()
    val displayName = field<String>("display_name")

    object Address : Nested("address") {
        val city by field<String>()
    }

    val address = nested(Address)
}

fun Row.toPersonList(): Person =
    Person(
        id = this[id],
        name = this[name],
        age = this[age],
        displayName = this[displayName],
        address =
            Person.Address(
                city = this[address.city],
            ),
    )

fun List<Row>.toPersonList(): List<Person> = map { it.toPersonList() }
