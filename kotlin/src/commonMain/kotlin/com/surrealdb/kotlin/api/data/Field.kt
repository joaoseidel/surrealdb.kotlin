package com.surrealdb.kotlin.api.data

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlin.reflect.KProperty1

/**
 * A typed reference to a field of [Table]'s record type, where [path] is the
 * SurrealQL that names it, already qualified with any enclosing groups.
 */
public class Field<V> internal constructor(
    public val path: String,
) {
    init {
        require(FIELD_PATH.matches(path)) {
            "Field path must match $FIELD_PATH (got '$path')"
        }
    }

    override fun toString(): String = path

    override fun equals(other: Any?): Boolean = other is Field<*> && other.path == path

    override fun hashCode(): Int = path.hashCode()

    internal companion object {
        val FIELD_PATH =
            Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*|\[(?:[0-9]+|\*)])*""")
    }
}

/**
 * A nested object inside a record, addressed by property reference:
 *
 * ```
 * object Books : Table<Book>("book", Book.serializer()) {
 *     val address by nested<Address>()
 * }
 *
 * Books.address[Address::city]        // Field<String> at "address.city"
 * ```
 *
 * [get] throws when a property is serialised under a different name
 * (`@SerialName`), naming the property.
 */
public class Nested<V> internal constructor(
    internal val path: String,
    internal val descriptor: SerialDescriptor?,
    internal val label: String,
) {
    /** The field this property is serialised as, qualified with this group's path. */
    public operator fun <R> get(property: KProperty1<V, R>): Field<R> =
        Field(childPath(path, resolveName(descriptor, property.name, label)))

    /** Descend into a further nested object. */
    public fun <R> nested(property: KProperty1<V, R>): Nested<R> {
        val name = resolveName(descriptor, property.name, label)
        val child = elementDescriptor(descriptor, name)
        return Nested(childPath(path, name), child, child?.let(::labelOf) ?: name)
    }
}

internal fun childPath(
    parent: String,
    name: String,
): String = if (parent.isEmpty()) name else "$parent.$name"

internal fun labelOf(descriptor: SerialDescriptor): String =
    descriptor.serialName.substringAfterLast('.').removeSuffix("?")

private fun SerialDescriptor.isWalkable(): Boolean = kind == StructureKind.CLASS || kind == StructureKind.OBJECT

private fun SerialDescriptor.knownNames(): List<String> = (0 until elementsCount).map { getElementName(it) }

private fun indexOf(
    descriptor: SerialDescriptor?,
    name: String,
    owner: String,
): Int? {
    if (descriptor == null || !descriptor.isWalkable()) return null
    val index = descriptor.getElementIndex(name)
    require(index >= 0) {
        "'$name' is not a field of $owner. Known fields: ${descriptor.knownNames().joinToString(", ")}."
    }
    return index
}

internal fun elementDescriptor(
    descriptor: SerialDescriptor?,
    name: String,
): SerialDescriptor? {
    val index = indexOf(descriptor, name, descriptor?.let(::labelOf) ?: name) ?: return null
    return descriptor?.getElementDescriptor(index)
}

internal fun resolveName(
    descriptor: SerialDescriptor?,
    name: String,
    owner: String,
): String {
    if (descriptor == null || !descriptor.isWalkable()) return name
    require(descriptor.getElementIndex(name) >= 0) {
        "$owner.$name is not a serialized field of $owner — @SerialName? " +
            "Known fields: ${descriptor.knownNames().joinToString(", ")}."
    }
    return name
}

internal fun walkPath(
    root: SerialDescriptor?,
    path: String,
    owner: String,
): SerialDescriptor? {
    var current = root
    var label = owner
    for (segment in path.split('.')) {
        val name = segment.substringBefore('[')
        val index = indexOf(current, name, label) ?: return null
        current = current?.getElementDescriptor(index)
        var indices = segment.removePrefix(name)
        while (indices.isNotEmpty()) {
            indices = indices.substringAfter(']')
            current = current?.takeIf { it.kind == StructureKind.LIST }?.getElementDescriptor(0)
            if (current == null) return null
        }
        label = current?.let(::labelOf) ?: name
    }
    return current
}
