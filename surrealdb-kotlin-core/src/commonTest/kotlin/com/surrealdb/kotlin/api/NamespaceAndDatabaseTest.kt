package com.surrealdb.kotlin.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class NamespaceAndDatabaseTest :
    ShouldSpec({
        context("Namespace and Database") {
            should("refuse a blank name, because the server defines a namespace whose name is the empty string") {
                shouldThrow<IllegalArgumentException> { Namespace("") }
                shouldThrow<IllegalArgumentException> { Database("  ") }
            }

            should("read back as the name they carry") {
                Namespace("app").value shouldBe "app"
                Database("prod").toString() shouldBe "prod"
            }
        }
    })
