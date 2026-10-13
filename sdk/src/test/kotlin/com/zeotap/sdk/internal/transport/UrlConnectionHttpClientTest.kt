package com.zeotap.sdk.internal.transport

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

class UrlConnectionHttpClientTest {
    private val server = MockWebServer()
    private val client = UrlConnectionHttpClient(connectTimeoutMs = 2_000, readTimeoutMs = 500)
    private val headers = linkedMapOf(
        "Content-Type" to "application/json",
        "X-Write-Key" to "wk_test",
        "User-Agent" to "@zeotap/android-sdk/test",
    )
    private val body = "{\"batch\":[{\"messageId\":\"m1\"}],\"sentAt\":\"2026-01-15T10:20:30.123Z\"}"

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/v1/batch").toString()

    @Test
    fun `POST body and headers arrive and the 2xx body is returned`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"success\":true,\"received\":1}"))

        val response = client.post(url(), headers, body.toByteArray(Charsets.UTF_8))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo("{\"success\":true,\"received\":1}")
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/batch")
        assertThat(recorded.getHeader("Content-Type")).isEqualTo("application/json")
        assertThat(recorded.getHeader("X-Write-Key")).isEqualTo("wk_test")
        assertThat(recorded.getHeader("User-Agent")).isEqualTo("@zeotap/android-sdk/test")
        assertThat(recorded.getHeader("Content-Length")).isEqualTo(body.toByteArray().size.toString())
        assertThat(recorded.body.readUtf8()).isEqualTo(body)
    }

    @Test
    fun `204 with no body yields empty string`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val response = client.post(url(), headers, body.toByteArray())
        assertThat(response.status).isEqualTo(204)
        assertThat(response.body).isEmpty()
    }

    @Test
    fun `non-2xx body is read from the error stream`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid JSON body\"}"))
        val response = client.post(url(), headers, body.toByteArray())
        assertThat(response.status).isEqualTo(400)
        assertThat(response.body).isEqualTo("{\"error\":\"invalid JSON body\"}")
    }

    @Test
    fun `401 and 500 return their status without throwing`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid write key\"}"))
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(client.post(url(), headers, body.toByteArray()).status).isEqualTo(401)
        val fiveHundred = client.post(url(), headers, body.toByteArray())
        assertThat(fiveHundred.status).isEqualTo(500)
        assertThat(fiveHundred.body).isEmpty()
    }

    @Test
    fun `utf-8 body bytes are preserved`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"echo\":\"ünïcödé 🎉\"}"))
        val payload = "{\"event\":\"ünïcödé 🎉\"}"
        val response = client.post(url(), headers, payload.toByteArray(Charsets.UTF_8))
        assertThat(response.body).isEqualTo("{\"echo\":\"ünïcödé 🎉\"}")
        assertThat(server.takeRequest().body.readUtf8()).isEqualTo(payload)
    }

    @Test
    fun `connection refused throws IOException`() {
        val gone = MockWebServer()
        gone.start()
        val dead = gone.url("/v1/batch").toString()
        gone.shutdown()
        assertFailsWith<IOException> { client.post(dead, headers, body.toByteArray()) }
    }

    @Test
    fun `read timeout throws IOException`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertFailsWith<IOException> { client.post(url(), headers, body.toByteArray()) }
    }

    @Test
    fun `non-http url throws IOException`() {
        assertFailsWith<IOException> { client.post("ftp://example.invalid/v1/batch", headers, body.toByteArray()) }
    }
}
