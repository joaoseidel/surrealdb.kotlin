package com.surrealdb.kotlin.query.api.data

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * What a [Table] and a [Nested] object inside one have in common: both name
 * fields.
 *
 * A group prefixes every field it declares with its own path, so one
 * declaration reads the same at any depth.
 */
public sealed class FieldGroup(
    internal val pathPrefix: String,
) {
    internal val declaredFields: MutableList<Field<*>> = mutableListOf()

    /** Declare a field, optionally as a dotted path into a nested object. */
    protected fun <V> field(name: String): Field<V> = Field<V>(pathPrefix + name).also { declaredFields += it }

    /** Declare a field taking its name from the property it initialises. */
    protected fun <V> field(): PropertyDelegateProvider<FieldGroup, ReadOnlyProperty<FieldGroup, Field<V>>> =
        PropertyDelegateProvider { _, property ->
            val resolved = field<V>(property.name)
            ReadOnlyProperty { _, _ -> resolved }
        }

    /**
     * Take [group] as part of this one: `val address = nested(Address)`.
     *
     * The call carries the group's fields into this group, in the order the
     * properties are written, and rejects a path that does not belong here.
     */
    protected fun <G : Nested> nested(group: G): G {
        require(group.pathPrefix.startsWith(pathPrefix)) {
            "A nested group names its whole path, because it cannot see the group it is declared in: " +
                "expected '${group.path.value}' to start with '$pathPrefix'."
        }
        declaredFields += group.declaredFields
        return group
    }
}
