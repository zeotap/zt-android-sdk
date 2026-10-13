package com.zeotap.sdk.internal.transport

import com.google.common.truth.Truth.assertThat
import com.zeotap.sdk.BuildConfig
import com.zeotap.sdk.internal.testing.FakeClock
import com.zeotap.sdk.internal.testing.RecordingSleep
import com.zeotap.sdk.internal.testing.ScriptedHttpClient
import com.zeotap.sdk.internal.util.NoopLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.IOException

/** Mirrors `sdk/js/src/transport.test.ts` `send()` plus the response-body parsing the web SDK lacks. */
class TransportTest {
    private val now = 1768472430123L // 2026-01-15T10:20:30.123Z

    private fun events(count: Int = 1): List<String> = List(count) { "{\"messageId\":\"test-${it + 1}\"}" }

    private class Harness(responses: List<Any>, apiHost: String = "https://api.example.com", nativeAppId: String = "") {
        val http = ScriptedHttpClient(responses.toMutableList())
        val sleep = RecordingSleep()
        val transport = Transport(apiHost, "wk_abc123", nativeAppId, http, FakeClock(1768472430123L), NoopLogger, sleep.sleep)

        fun send(payloads: List<String>): SendOutcome = runBlocking { transport.send(payloads) }
    }

    @Test
    fun `sends POST to apiHost v1 batch with the exact header set`() {
        val h = Harness(listOf(200))
        h.send(events())

        assertThat(h.http.requests).hasSize(1)
        val request = h.http.requests.single()
        assertThat(request.url).isEqualTo("https://api.example.com/v1/batch")
        assertThat(request.headers.keys.toList()).containsExactly("Content-Type", "X-Write-Key", "User-Agent").inOrder()
        assertThat(request.headers["Content-Type"]).isEqualTo("application/json")
        assertThat(request.headers["X-Write-Key"]).isEqualTo("wk_abc123")
        assertThat(request.headers["User-Agent"]).isEqualTo("@zeotap/android-sdk/${BuildConfig.SDK_VERSION}")
    }

    @Test
    fun `adds X-Native-App-ID only when configured`() {
        val h = Harness(listOf(200), nativeAppId = "5d6c3a0e-0000-4000-8000-000000000001")
        h.send(events())
        val headers = h.http.requests.single().headers
        assertThat(headers.keys.toList()).containsExactly("Content-Type", "X-Write-Key", "X-Native-App-ID", "User-Agent").inOrder()
        assertThat(headers["X-Native-App-ID"]).isEqualTo("5d6c3a0e-0000-4000-8000-000000000001")
    }

    @Test
    fun `body is the batch envelope with sentAt from the clock`() {
        val h = Harness(listOf(200))
        h.send(events(2))

        val body = h.http.requests.single().body
        assertThat(body).isEqualTo(
            "{\"batch\":[{\"messageId\":\"test-1\"},{\"messageId\":\"test-2\"}],\"sentAt\":\"2026-01-15T10:20:30.123Z\"}",
        )
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertThat(parsed.keys.toList()).containsExactly("batch", "sentAt").inOrder()
        assertThat(parsed["batch"]!!.jsonArray).hasSize(2)
        assertThat(parsed["sentAt"]!!.jsonPrimitive.content).isEqualTo("2026-01-15T10:20:30.123Z")
    }

    @Test
    fun `200 with parsed body reports ingest's per-event verdict`() {
        val h = Harness(listOf(HttpResponse(200, "{\"success\":false,\"received\":1,\"errors\":[\"event 1: x\"]}")))
        val outcome = h.send(events(2)) as SendOutcome.Success
        assertThat(outcome.result.status).isEqualTo(200)
        assertThat(outcome.result.success).isFalse()
        assertThat(outcome.result.received).isEqualTo(1)
        assertThat(outcome.result.sent).isEqualTo(2)
        assertThat(outcome.result.errors).containsExactly("event 1: x")
    }

    @Test
    fun `200 with full success body`() {
        val h = Harness(listOf(HttpResponse(200, "{\"success\":true,\"received\":3}")))
        val outcome = h.send(events(3)) as SendOutcome.Success
        assertThat(outcome.result.success).isTrue()
        assertThat(outcome.result.received).isEqualTo(3)
        assertThat(outcome.result.errors).isEmpty()
    }

    @Test
    fun `malformed or empty 2xx body still counts as success`() {
        for (body in listOf("", "   ", "not json", "[1,2]", "\"str\"")) {
            val h = Harness(listOf(HttpResponse(204, body)))
            val outcome = h.send(events(2)) as SendOutcome.Success
            assertThat(outcome.result.status).isEqualTo(204)
            assertThat(outcome.result.success).isTrue()
            assertThat(outcome.result.received).isEqualTo(2)
            assertThat(outcome.result.sent).isEqualTo(2)
            assertThat(outcome.result.errors).isEmpty()
        }
    }

    @Test
    fun `empty payload list makes no request and succeeds`() {
        val h = Harness(emptyList())
        val outcome = h.send(emptyList()) as SendOutcome.Success
        assertThat(h.http.requests).isEmpty()
        assertThat(h.sleep.delays).isEmpty()
        assertThat(outcome.result).isEqualTo(com.zeotap.sdk.BatchResult(200, true, 0, 0, emptyList()))
    }

    @Test
    fun `retries up to 3 times with exponential backoff on 500`() {
        val h = Harness(listOf(500, 500, 500, 200))
        val outcome = h.send(events())
        assertThat(outcome).isInstanceOf(SendOutcome.Success::class.java)
        assertThat(h.http.requests).hasSize(4)
        assertThat(h.sleep.delays).containsExactly(500L, 1000L, 2000L).inOrder()
    }

    @Test
    fun `retries on 429`() {
        val h = Harness(listOf(429, 200))
        val outcome = h.send(events())
        assertThat(outcome).isInstanceOf(SendOutcome.Success::class.java)
        assertThat(h.http.requests).hasSize(2)
        assertThat(h.sleep.delays).containsExactly(500L)
    }

    @Test
    fun `retries on 503 and 502`() {
        val h = Harness(listOf(503, 502, 200))
        h.send(events())
        assertThat(h.http.requests).hasSize(3)
        assertThat(h.sleep.delays).containsExactly(500L, 1000L).inOrder()
    }

    @Test
    fun `400 is rejected immediately with the body`() {
        val h = Harness(listOf(HttpResponse(400, "{\"error\":\"invalid JSON body\"}")))
        val outcome = h.send(events()) as SendOutcome.Rejected
        assertThat(outcome.status).isEqualTo(400)
        assertThat(outcome.body).isEqualTo("{\"error\":\"invalid JSON body\"}")
        assertThat(h.http.requests).hasSize(1)
        assertThat(h.sleep.delays).isEmpty()
    }

    @Test
    fun `401 is rejected immediately`() {
        val h = Harness(listOf(401))
        val outcome = h.send(events()) as SendOutcome.Rejected
        assertThat(outcome.status).isEqualTo(401)
        assertThat(h.http.requests).hasSize(1)
        assertThat(h.sleep.delays).isEmpty()
    }

    @Test
    fun `non-retryable status after a retry still rejects immediately`() {
        val h = Harness(listOf(500, 400))
        val outcome = h.send(events()) as SendOutcome.Rejected
        assertThat(outcome.status).isEqualTo(400)
        assertThat(h.http.requests).hasSize(2)
        assertThat(h.sleep.delays).containsExactly(500L)
    }

    @Test
    fun `IOException retries then succeeds`() {
        val h = Harness(listOf(IOException("connect refused"), IOException("timeout"), 200))
        val outcome = h.send(events())
        assertThat(outcome).isInstanceOf(SendOutcome.Success::class.java)
        assertThat(h.http.requests).hasSize(3)
        assertThat(h.sleep.delays).containsExactly(500L, 1000L).inOrder()
    }

    @Test
    fun `exhaustion on 5xx reports attempts and last status`() {
        val h = Harness(listOf(500, 502, 503, 500))
        val outcome = h.send(events()) as SendOutcome.Exhausted
        assertThat(outcome.attempts).isEqualTo(4)
        assertThat(outcome.lastStatus).isEqualTo(500)
        assertThat(outcome.error).isNull()
        assertThat(h.http.requests).hasSize(4)
        assertThat(h.sleep.delays).containsExactly(500L, 1000L, 2000L).inOrder()
    }

    @Test
    fun `exhaustion on network errors reports the last error`() {
        val last = IOException("last")
        val h = Harness(listOf(IOException("1"), IOException("2"), IOException("3"), last))
        val outcome = h.send(events()) as SendOutcome.Exhausted
        assertThat(outcome.attempts).isEqualTo(4)
        assertThat(outcome.lastStatus).isNull()
        assertThat(outcome.error).isSameInstanceAs(last)
    }

    @Test
    fun `mixed failures keep both the last status and the last error`() {
        val err = IOException("net")
        val h = Harness(listOf(503, err, 500, err))
        val outcome = h.send(events()) as SendOutcome.Exhausted
        assertThat(outcome.lastStatus).isEqualTo(500)
        assertThat(outcome.error).isSameInstanceAs(err)
    }

    @Test
    fun `same body bytes on every attempt`() {
        val h = Harness(listOf(500, IOException("x"), 429, 200))
        h.send(events(3))
        val bodies = h.http.requests.map { it.body }.toSet()
        assertThat(bodies).hasSize(1)
        val headers = h.http.requests.map { it.headers }.toSet()
        assertThat(headers).hasSize(1)
    }

    @Test
    fun `strips trailing slash from apiHost and keeps a path prefix`() {
        val a = Harness(listOf(200), apiHost = "https://api.example.com/")
        a.send(events())
        assertThat(a.http.requests.single().url).isEqualTo("https://api.example.com/v1/batch")

        val b = Harness(listOf(200), apiHost = "https://www.site.com/cdp")
        b.send(events())
        assertThat(b.http.requests.single().url).isEqualTo("https://www.site.com/cdp/v1/batch")
    }

    @Test
    fun `constants match the web SDK`() {
        assertThat(Transport.MAX_RETRIES).isEqualTo(3)
        assertThat(Transport.BASE_DELAY_MS).isEqualTo(500L)
        assertThat(Transport.RESERVE_MAX_BYTES).isEqualTo(4L * 1024 * 1024)
    }
}
