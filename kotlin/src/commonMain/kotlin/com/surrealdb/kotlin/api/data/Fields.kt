package com.surrealdb.kotlin.api.data

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

@DslMarker
public annotation class SurqlDsl

/**
 * The declaration mechanism shared by [Table] and every group inside one: a
 * path prefix plus the serial descriptor the names in this group are checked
 * against.
 *
 * `field("pagse")` throws when the declaration initialises, naming the field
 * and listing what the record type actually serialises.
 */
@SurqlDsl
public abstract class Fields internal constructor(
    internal val groupPath: String,
    internal val groupDescriptor: SerialDescriptor?,
    internal val groupLabel: String,
) {
    internal val declaredFields: MutableList<Field<*>> = mutableListOf()

    /** Declare a field, optionally as a dotted path into nested objects. */
    protected fun <V> field(name: String): Field<V> {
        walkPath(groupDescriptor, name, groupLabel)
        return Field<V>(childPath(groupPath, name)).also { declaredFields += it }
    }

    /** Declare a field taking its name from the property it initialises. */
    protected fun <V> field(): PropertyDelegateProvider<Fields, ReadOnlyProperty<Fields, Field<V>>> =
        PropertyDelegateProvider { _, property ->
            val resolved = field<V>(property.name)
            ReadOnlyProperty { _, _ -> resolved }
        }

    /** Declare a nested object, addressed by property reference. */
    protected fun <V> nested(name: String): Nested<V> {
        val descriptor = walkPath(groupDescriptor, name, groupLabel)
        val group = Nested<V>(childPath(groupPath, name), descriptor, descriptor?.let(::labelOf) ?: name)
        declaredFields += group
        return group
    }

    /** Declare a nested object taking its name from the property it initialises. */
    protected fun <V> nested(): PropertyDelegateProvider<Fields, ReadOnlyProperty<Fields, Nested<V>>> =
        PropertyDelegateProvider { _, property ->
            val resolved = nested<V>(property.name)
            ReadOnlyProperty { _, _ -> resolved }
        }
}
