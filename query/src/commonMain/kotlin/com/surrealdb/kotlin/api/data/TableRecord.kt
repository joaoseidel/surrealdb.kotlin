package com.surrealdb.kotlin.api.data

public class TableRecord<S : Table> internal constructor(
    internal val schema: S,
    public val record: RecordId,
) : Target {
    override fun toString(): String = record.toString()

    override fun equals(other: Any?): Boolean = other is TableRecord<*> && other.record == record

    override fun hashCode(): Int = record.hashCode()
}

public operator fun <S : Table> S.get(id: String): TableRecord<S> = TableRecord(this, RecordId(tableName, id))
