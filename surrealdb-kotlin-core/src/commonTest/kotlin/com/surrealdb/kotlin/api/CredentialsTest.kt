package com.surrealdb.kotlin.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

class CredentialsTest :
    ShouldSpec({
        context("the parameters a credential sends") {
            should(
                "carry user and pass alone for a root user, because ns beside them signs in at a level root is not",
            ) {
                val params = Credentials.RootUser("root", "secret").toParams()

                params.keys shouldContainExactly setOf("user", "pass")
                params.text("user") shouldBe "root"
                params.text("pass") shouldBe "secret"
            }

            should("add ns and no db for a namespace user, because a db beside it selects the database level") {
                val params = Credentials.NamespaceUser(Namespace("app"), "editor", "secret").toParams()

                params.keys shouldContainExactly setOf("user", "pass", "ns")
                params.text("ns") shouldBe "app"
            }

            should("add both ns and db for a database user") {
                val params = Credentials.DatabaseUser(Namespace("app"), Database("prod"), "editor", "secret").toParams()

                params.keys shouldContainExactly setOf("user", "pass", "ns", "db")
                params.text("ns") shouldBe "app"
                params.text("db") shouldBe "prod"
            }

            should("flatten a record user's variables beside ac, because the server does not read a vars key") {
                val vars =
                    buildJsonObject {
                        put("email", "ada@example.com")
                        put("pass", "secret")
                    }

                val params = Credentials.RecordUser(Namespace("app"), Database("prod"), "account", vars).toParams()

                params.keys shouldContainExactly setOf("ns", "db", "ac", "email", "pass")
                params.text("ac") shouldBe "account"
                params.text("email") shouldBe "ada@example.com"
                params["vars"] shouldBe null
            }

            should("send a raw escape exactly as given, checking nothing") {
                val given = buildJsonObject { put("anything", JsonPrimitive(1)) }

                Credentials.Raw(given).toParams() shouldBe given
            }
        }

        context("Credentials.RecordUser") {
            should("refuse a variable named after a key the server binds itself") {
                val vars = buildJsonObject { put("ns", "somewhere-else") }

                val thrown =
                    shouldThrow<IllegalArgumentException> {
                        Credentials.RecordUser(Namespace("app"), Database("prod"), "account", vars)
                    }

                thrown.message.orEmpty() shouldContain "cannot be named ns"
            }
        }
    })
