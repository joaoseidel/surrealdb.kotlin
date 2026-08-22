package com.surrealdb.kotlin.runtime

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
private fun encode(
    text: String,
    urlSafe: Boolean,
): String {
    val b64 = Base64.encode(text.encodeToByteArray())
    return if (urlSafe) b64.trimEnd('=').replace('+', '-').replace('/', '_') else b64
}

private fun jwt(
    payloadJson: String,
    urlSafe: Boolean = true,
): String =
    "${encode("""{"alg":"HS256","typ":"JWT"}""", urlSafe)}." +
        "${encode(payloadJson, urlSafe)}." +
        encode("signature-bytes", urlSafe)

@OptIn(ExperimentalEncodingApi::class)
class JwtTest :
    ShouldSpec({
        context("parseJwtExpiryMillis") {
            context("a well-formed token") {
                should("return exp in millis, because the scheduler works in millis") {
                    parseJwtExpiryMillis(jwt("""{"exp":1700000000,"sub":"user"}""")) shouldBe 1_700_000_000_000L
                }

                should("accept standard base64, which some servers emit instead of URL-safe") {
                    parseJwtExpiryMillis(jwt("""{"exp":1234567890}""", urlSafe = false)) shouldBe 1_234_567_890_000L
                }

                should("accept a URL-safe payload whose padding was stripped") {
                    // 10 bytes → 16 base64 chars, two '=' stripped.
                    parseJwtExpiryMillis("h.${encode("""{"exp":12}""", urlSafe = true)}.s") shouldBe 12_000L
                }

                should("accept a URL-safe payload that needed no padding at all") {
                    parseJwtExpiryMillis("h.${encode("""{"exp":1}""", urlSafe = true)}.s") shouldBe 1_000L
                }
            }

            context("a token it cannot read") {
                // Renewal is scheduled off this value, so every unreadable shape must answer null
                // rather than throw — a malformed token must not take down sign-in.
                should("answer null when there is no exp claim") {
                    parseJwtExpiryMillis(jwt("""{"sub":"user","iat":1700000000}""")).shouldBeNull()
                }

                should("answer null for an empty string") {
                    parseJwtExpiryMillis("").shouldBeNull()
                }

                should("answer null for a token with one segment") {
                    parseJwtExpiryMillis("just-a-string").shouldBeNull()
                }

                should("answer null when the payload is not base64") {
                    parseJwtExpiryMillis("header.@@@not-base64@@@.signature").shouldBeNull()
                }

                should("answer null when the payload is not JSON") {
                    parseJwtExpiryMillis("header.${encode("not json at all", urlSafe = true)}.signature")
                        .shouldBeNull()
                }

                should("answer null when exp is not a number") {
                    parseJwtExpiryMillis(jwt("""{"exp":"not-a-number"}""")).shouldBeNull()
                }

                should("decode '-' and '_' without crashing, even when the result is not JSON") {
                    // Standard base64 of these bytes is "+/+/", URL-safe "-_-_".
                    val raw = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte())
                    val urlSafe =
                        Base64
                            .encode(raw)
                            .trimEnd('=')
                            .replace('+', '-')
                            .replace('/', '_')

                    parseJwtExpiryMillis("h.$urlSafe.s").shouldBeNull()
                }
            }
        }
    })
