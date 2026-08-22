package com.surrealdb.kotlin.query.api.query

import com.surrealdb.kotlin.core.api.data.RecordId
import com.surrealdb.kotlin.core.api.query.BoundQuery
import com.surrealdb.kotlin.core.api.query.QueryContext
import com.surrealdb.kotlin.query.api.data.Table
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Snapshot tests for each builder's compiled [BoundQuery]. No server is needed
 *; every test asserts on the SurrealQL + bindings produced by `compile()`.
 *
 * These tests are the contract for the query-construction layer: any change in
 * the emitted SurrealQL is a change in the wire protocol's behaviour and
 * should be intentional.
 */
class QueryBuilderTest {
    private val context =
        object : QueryContext {
            override val json: Json = Json

            override suspend fun query(bound: BoundQuery): JsonElement = error("not used in compile-only tests")
        }

    private fun bindings(q: BoundQuery): Map<String, JsonElement> = q.bindings

    @Test
    fun `select from table emits SELECT FROM with bound table name`() {
        val q = context.select(People).compile()
        assertTrue(q.surql.startsWith("SELECT * FROM type::table("))
        assertEquals(1, q.bindings.size)
        assertEquals(
            "person",
            q.bindings.values
                .first()
                .toString()
                .trim('"'),
        )
    }

    @Test
    fun `select from record id binds table and id separately`() {
        val q = context.select(RecordId("person", "alice")).compile()
        assertTrue(q.surql.startsWith("SELECT * FROM ONLY type::record("))
        assertEquals(2, q.bindings.size)
    }

    @Test
    fun `select fields emits comma-separated field list`() {
        val q = context.select(People).fields(People.id, People.name, People.age).compile()
        assertTrue(q.surql.startsWith("SELECT id, name, age FROM type::table("))
    }

    @Test
    fun `select value emits VALUE clause`() {
        val q = context.select(People).value(People.name).compile()
        assertTrue(q.surql.startsWith("SELECT VALUE name FROM type::table("))
    }

    @Test
    fun `select where compiles expression with bound value`() {
        val q =
            context
                .select(People)
                .where { age greater 18 }
                .compile()
        assertTrue(q.surql.contains(" WHERE (age > "))
    }

    @Test
    fun `select limit and start bind values`() {
        val q =
            context
                .select(People)
                .start(10)
                .limit(5)
                .compile()
        assertTrue(q.surql.contains(" START "))
        assertTrue(q.surql.contains(" LIMIT "))
    }

    @Test
    fun `select fetch emits comma-separated field list`() {
        val q =
            context
                .select(Posts)
                .fetch(Posts.author, Posts.comments)
                .compile()
        assertTrue(q.surql.endsWith(" FETCH author, comments"))
    }

    @Test
    fun `create content binds the data object`() {
        val q =
            context
                .create(RecordId("person", "1"))
                .content(buildJsonObject { put("name", JsonPrimitive("Ada")) })
                .compile()
        assertTrue(q.surql.startsWith("CREATE ONLY type::record("))
        assertTrue(q.surql.contains(" CONTENT "))
    }

    @Test
    fun `update content compiles to UPDATE ONLY CONTENT`() {
        val q =
            context
                .update(RecordId("person", "1"))
                .content(buildJsonObject { put("name", JsonPrimitive("X")) })
                .compile()
        assertTrue(q.surql.startsWith("UPDATE ONLY type::record("))
        assertTrue(q.surql.contains(" CONTENT "))
    }

    @Test
    fun `upsert with where compiles to UPSERT then WHERE`() {
        val q =
            context
                .upsert(People)
                .content(buildJsonObject { put("name", JsonPrimitive("X")) })
                .where { email eq "x@y.z" }
                .compile()
        assertTrue(q.surql.startsWith("UPSERT type::table("))
        assertTrue(q.surql.contains(" CONTENT "))
        assertTrue(q.surql.contains(" WHERE (email = "))
    }

    @Test
    fun `merge compiles to UPDATE ONLY MERGE`() {
        val q =
            context
                .merge(RecordId("person", "1"), buildJsonObject { put("active", JsonPrimitive(true)) })
                .compile()
        assertTrue(q.surql.startsWith("UPDATE ONLY type::record("))
        assertTrue(q.surql.contains(" MERGE "))
    }

    @Test
    fun `patch with a returnMode appends the RETURN clause`() {
        val q = context.patch(RecordId("person", "1"), buildJsonObject {}).returnMode(ReturnMode.Diff).compile()
        assertTrue(q.surql.endsWith(" RETURN DIFF"))
    }

    @Test
    fun `delete with where compiles to DELETE then WHERE`() {
        val q =
            context
                .delete(People)
                .where { active eq false }
                .compile()
        assertTrue(q.surql.startsWith("DELETE type::table("))
        assertTrue(q.surql.contains(" WHERE (active = "))
    }

    @Test
    fun `relate compiles to arrow chain`() {
        val q =
            RelateQuery(
                context,
                RecordId("person", "a"),
                Table("likes"),
                RecordId("person", "b"),
            ).compile()
        assertTrue(q.surql.contains("->"))
        assertTrue(q.surql.startsWith("RELATE "))
    }

    @Test
    fun `insert compiles to INSERT INTO with bound table and data`() {
        val q =
            InsertQuery(
                context,
                Table("person"),
                buildJsonObject { put("name", JsonPrimitive("A")) },
            ).compile()
        assertTrue(q.surql.startsWith("INSERT INTO $"))
    }

    @Test
    fun `insertRelation compiles to INSERT RELATION INTO`() {
        val q =
            InsertRelationQuery(
                context,
                Table("likes"),
                buildJsonObject { put("in", JsonPrimitive("p:a")) },
            ).compile()
        assertTrue(q.surql.startsWith("INSERT RELATION INTO $"))
    }

    @Test
    fun `run with no args compiles to empty parens`() {
        val q = RunQuery(context, "fn::greet").compile()
        assertEquals("fn::greet()", q.surql)
    }

    @Test
    fun `run with args binds each one`() {
        val q = RunQuery(context, "fn::greet").args("alice", 42).compile()
        assertTrue(q.surql.startsWith("fn::greet("))
        assertEquals(2, q.bindings.size)
    }

    @Test
    fun `run rejects invalid function names`() {
        assertFailsWith<IllegalArgumentException> {
            RunQuery(context, "fn::; DROP TABLE x")
        }
    }

    @Test
    fun `run rejects invalid version`() {
        assertFailsWith<IllegalArgumentException> {
            RunQuery(context, "fn::ok", version = "not-a-version")
        }
    }
}
