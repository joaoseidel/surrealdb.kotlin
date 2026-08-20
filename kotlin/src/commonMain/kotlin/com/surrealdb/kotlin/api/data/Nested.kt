package com.surrealdb.kotlin.api.data

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
 * A group is not a [Field], so there is nothing to assign or read whole:
 * `it[Users.address.city] = "Boston"` writes the leaf, and a dotted `SET`
 * builds the object above it. A caller who wants the whole object at once
 * declares `field<Postal>("address")` beside the group.
 */
public open class Nested(
    public val path: String,
) : FieldGroup("$path.") {
    init {
        require(Field.FIELD_PATH.matches(path)) {
            "A nested group's path must match ${Field.FIELD_PATH} (got '$path')"
        }
    }

    override fun toString(): String = path
}
