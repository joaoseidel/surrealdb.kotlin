package com.surrealdb.kotlin.core.api

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode

private fun clientOn(url: String): Surreal =
    Surreal(
        Surreal.Config(
            url = url,
            autoConnect = false,
            httpClientFactory = { _ -> HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) },
        ),
    )

class FeatureSetTest :
    ShouldSpec(
        {
            context("Surreal.features") {
                should("advertise only export and import on HTTP, because that is all the HTTP engine implements") {
                    clientOn("http://localhost:8000").features shouldBe setOf(Feature.ExportImport)
                }

                should("leave export and import off WebSocket, because the server has no such RPC") {
                    clientOn("ws://localhost:8000").features shouldBe
                        setOf(Feature.LiveQueries, Feature.Sessions, Feature.Transactions, Feature.RefreshTokens)
                }

                should("advertise SurrealML on neither engine until something implements it") {
                    (Feature.SurrealML in clientOn("http://localhost:8000").features) shouldBe false
                    (Feature.SurrealML in clientOn("wss://localhost:8000").features) shouldBe false
                }
            }
        },
    )
