package com.surrealdb.kotlin.query.api.data

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.data.RecordKey
import com.surrealdb.kotlin.core.api.data.Target
import kotlin.uuid.Uuid

public class TableRecord<S : Table> internal constructor(
    internal val schema: S,
    public val record: RecordId,
) : Target {
    override fun toString(): String = record.toString()

    override fun equals(other: Any?): Boolean = other is TableRecord<*> && other.record == record

    override fun hashCode(): Int = record.hashCode()
}

public operator fun <S : Table> S.get(id: String): TableRecord<S> = TableRecord(this, RecordId(tableName, id))

public operator fun <S : Table> S.get(id: Long): TableRecord<S> = TableRecord(this, RecordId(tableName, id))

public operator fun <S : Table> S.get(id: Uuid): TableRecord<S> = TableRecord(this, RecordId(tableName, id))

public operator fun <S : Table> S.get(key: RecordKey): TableRecord<S> = TableRecord(this, RecordId(tableName, key))
