package com.surrealdb.kotlin.query.api.data

import com.surrealdb.kotlin.core.api.data.RecordId

/**
 * A relation table, which SurrealDB gives two fields of its own: the record an
 * edge leaves and the one it arrives at.
 *
 * ```
 * object Authored : EdgeTable("authored")
 *
 * db.delete(Authored).where { (`in` eq ada) and (out eq hobbit) }
 * ```
 *
 * They are declared here rather than on each relation because SurrealDB fixes
 * their names, so a filter on one end reads the same whichever edge it is
 * written against. An edge carrying fields of its own declares them as usual.
 */
public open class EdgeTable(
    tableName: String,
) : Table(tableName) {
    public val `in`: Field<RecordId> = field("in")
    public val out: Field<RecordId> = field("out")
}
