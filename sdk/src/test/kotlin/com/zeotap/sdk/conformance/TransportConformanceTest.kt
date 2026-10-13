package com.zeotap.sdk.conformance

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.zeotap.sdk.internal.testing.FakeClock
import com.zeotap.sdk.internal.testing.RecordingLogger
import com.zeotap.sdk.internal.testing.RecordingSleep
import com.zeotap.sdk.internal.testing.ScriptedHttpClient
import com.zeotap.sdk.internal.transport.SendOutcome
import com.zeotap.sdk.internal.transport.Transport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.IOException

/**
 * `sdk/conformance/vectors/transport.vectors.json` against [Transport].
 *
 * Outcome mapping: web `succeeded` ⇔ [SendOutcome.Success], `threw` ⇔
 * [SendOutcome.Rejected] (the web's `Ingest API error <status>: <body>` message
 * is reconstructed from status + body), `exhausted` ⇔ [SendOutcome.Exhausted].
 * Header comparison ignores `User-Agent`: the vectors describe the browser's
 * fetch(), where the UA is not settable; Android adds it (additive, documented
 * in `runners/README.md`). `keepalive`/`credentials` are fetch()-only options.
 */
@RunWith(Parameterized::class)
class TransportConformanceTest(private val id: String, private val case: JsonObject) {
    companion object {
        private const val FIXED_NOW_MS = 1768472430123L // 2026-01-15T10:20:30.123Z

        @JvmStatic
        @Parameterized.Parameters(name = "transport/{0}")
        fun cases(): Collection<Array<Any>> = VectorLoader.parameters("transport")
    }

    @Test
    fun conforms() {
        val input = case["input"]!!.jsonObject
        val expected = case["expected"]!!.jsonObject

        val script: MutableList<Any> = input["responses"]!!.jsonArray.map { element ->
            val primitive = element.jsonPrimitive
            primitive.intOrNull ?: when (primitive.content) {
                "network" -> IOException("network")
                else -> error("$id: unsupported scripted response $primitive")
            }
        }.toMutableList()
        val http = ScriptedHttpClient(script)
        val sleep = RecordingSleep()
        val transport = Transport(
            apiHost = input["apiHost"]!!.jsonPrimitive.content,
            writeKey = input["writeKey"]!!.jsonPrimitive.content,
            nativeAppId = input["nativeAppId"]!!.jsonPrimitive.content,
            http = http,
            clock = FakeClock(FIXED_NOW_MS),
            logger = RecordingLogger(),
            sleep = sleep.sleep,
        )
        val payloads = List(input["eventCount"]!!.jsonPrimitive.int) { "{\"messageId\":\"test-${it + 1}\"}" }

        val outcome = runBlocking { transport.send(payloads) }

        assertWithMessage("$id attempts").that(http.requests).hasSize(expected["attempts"]!!.jsonPrimitive.int)
        assertWithMessage("$id delaysMs").that(sleep.delays)
            .containsExactlyElementsIn(expected["delaysMs"]!!.jsonArray.map { it.jsonPrimitive.long }).inOrder()

        val expectedOutcome = expected["outcome"]!!.jsonPrimitive.content
        when (outcome) {
            is SendOutcome.Success -> assertWithMessage("$id outcome").that(expectedOutcome).isEqualTo("succeeded")
            is SendOutcome.Exhausted -> {
                assertWithMessage("$id outcome").that(expectedOutcome).isEqualTo("exhausted")
                assertWithMessage("$id attempts").that(outcome.attempts).isEqualTo(http.requests.size)
            }
            is SendOutcome.Rejected -> {
                assertWithMessage("$id outcome").that(expectedOutcome).isEqualTo("threw")
                val prefix = expected["errorMessagePrefix"]?.jsonPrimitive?.content
                if (prefix != null) {
                    val message = "Ingest API error ${outcome.status}: ${outcome.body}"
                    assertWithMessage("$id errorMessagePrefix").that(message).startsWith(prefix)
                }
            }
        }

        val request = expected["request"]?.jsonObject
        if (request != null) {
            val first = http.requests.first()
            assertWithMessage("$id url").that(first.url).isEqualTo(request["url"]!!.jsonPrimitive.content)
            val expectedHeaders = request["headers"]!!.jsonObject.mapValues { (it.value as JsonPrimitive).content }
            val actualHeaders = first.headers.filterKeys { it != "User-Agent" }
            assertWithMessage("$id headers").that(actualHeaders).containsExactlyEntriesIn(expectedHeaders)
        }

        val envelope = expected["envelope"]?.jsonObject
        if (envelope != null) {
            val body = Json.parseToJsonElement(http.requests.first().body).jsonObject
            assertWithMessage("$id envelope keys").that(body.keys.toList())
                .containsExactlyElementsIn(envelope["keys"]!!.jsonArray.map { it.jsonPrimitive.content }).inOrder()
            assertWithMessage("$id batchLength").that(body["batch"]!!.jsonArray)
                .hasSize(envelope["batchLength"]!!.jsonPrimitive.int)
            assertWithMessage("$id sentAt").that(body["sentAt"]!!.jsonPrimitive.content)
                .isEqualTo(envelope["sentAt"]!!.jsonPrimitive.content)
        }

        if (expected["sameBodyOnRetry"]?.jsonPrimitive?.content == "true" && http.requests.size > 1) {
            assertWithMessage("$id sameBodyOnRetry").that(http.requests.map { it.body }.toSet()).hasSize(1)
        }
        assertThat(http.requests.size).isAtMost(Transport.MAX_RETRIES + 1)
    }
}
