package com.surrealdb.kotlin.query.api.data

import com.surrealdb.kotlin.core.api.data.FieldPath
import com.surrealdb.kotlin.core.api.data.Projection

/**
 * The fields of one object field of a [Table], named once and reached through
 * the property that adopts them.
 *
 * ```
 * object Users : Table("user") {
 *     val name by field<String>()
 *
 *     object Address : Nested("address") {
 *         val city by field<String>()
 *         val zip = field<String>("postal_code")
 *     }
 *
 *     val address = nested(Address)
 * }
 *
 * db.select(Users).where { address.city eq "Boston" }
 * row[Users.address.city]
 * ```
 *
 * [path] is the whole path from the table, so a group inside a group is
 * `Nested("address.geo")`.
 *
 * A group is not a [Field], so there is nothing to assign. Assigning the leaf,
 * `it[Users.address.city] = "Boston"`, builds the object above it with a dotted
 * `SET`. It is a [Projection], so `fields(Users.address)` asks for the whole
 * object and every leaf under it still reads through its own declaration. A
 * caller who wants the object as one value declares `field<Postal>("address")`
 * beside the group.
 */
public open class Nested(
    path: String,
) : FieldGroup("${FieldPath(path).value}."),
    Projection {
    override val path: FieldPath = FieldPath(path)

    override fun toString(): String = path.value
}
