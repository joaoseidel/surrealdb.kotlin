package com.surrealdb.kotlin.api.query

import com.surrealdb.kotlin.api.data.Table
import com.surrealdb.kotlin.api.error.SurrealProtocolException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compare each declared table against the schema the database holds, and return
 * one message per difference. An empty list means every declared field exists.
 *
 * ```
 * db.checkSchema(Users, Books) shouldBe emptyList()
 * // "user.pagse is not defined on the server. Known fields: age, name."
 * ```
 *
 * A `WHERE` naming a field the record does not have is answered with an empty
 * result set rather than an error, so a misspelt name is a query that silently
 * matches nothing. This is the check that makes it loud, and it belongs in a
 * test against a real database or in a startup assertion.
 *
 * Only a SCHEMAFULL table can answer the question. `INFO FOR TABLE` reports no
 * fields at all for a SCHEMALESS one, and reports that same empty shape for a
 * table nobody has defined, so both are returned as messages of their own
 * rather than counted as agreement.
 *
 * The record id is never compared: SurrealDB fixes it at `id` and leaves it out
 * of the field list whatever the schema says.
 */
public suspend fun QueryContext.checkSchema(vararg tables: Table<*>): List<String> {
    if (tables.isEmpty()) return emptyList()

    val request = BoundQuery("INFO FOR DB")
    tables.forEach { table ->
        request.appendLiteral("; INFO FOR TABLE ")
        request.bind(JsonPrimitive(table.tableName))
    }

    val statements = statementResults(query(request))
    if (statements.size != tables.size + 1) {
        throw SurrealProtocolException(
            "Expected ${tables.size + 1} statement results from INFO, got ${statements.size}",
        )
    }

    val definitions = statements.first().objectAt("tables")
    return tables.withIndex().flatMap { (index, table) ->
        val definition = definitions[table.tableName]?.jsonPrimitive?.content
        when {
            definition == null -> {
                listOf("${table.tableName} is not defined on the server, so its fields cannot be checked.")
            }

            !definition.split(' ').contains("SCHEMAFULL") -> {
                listOf("${table.tableName} is not SCHEMAFULL, so the server does not know which fields it has.")
            }

            else -> {
                table.driftAgainst(statements[index + 1].objectAt("fields").keys)
            }
        }
    }
}

private fun Table<*>.driftAgainst(known: Set<String>): List<String> =
    declaredFields
        .map { it.path }
        .filter { it != "id" && serverPaths(it).none(known::contains) }
        .map { "$tableName.$it is not defined on the server. Known fields: ${known.joinToString(", ")}." }

/**
 * `INFO FOR TABLE` names an array's element type `tags.*` and the array itself
 * `tags`, while a declaration writes an index: `field<String>("tags[0]")`. Both
 * readings are accepted so an indexed path is not reported as missing.
 */
private fun serverPaths(path: String): List<String> =
    listOf(path.replace(INDEX_SEGMENT, ""), path.replace(INDEX_SEGMENT, ".*"))

private val INDEX_SEGMENT = Regex("""\[(?:[0-9]+|\*)]""")

private fun JsonElement.objectAt(key: String): JsonObject =
    (this as? JsonObject)?.get(key) as? JsonObject
        ?: throw SurrealProtocolException("Expected an object at '$key' in an INFO response, got: $this")
