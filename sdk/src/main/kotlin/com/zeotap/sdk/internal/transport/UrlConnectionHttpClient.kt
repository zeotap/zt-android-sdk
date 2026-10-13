package com.zeotap.sdk.internal.transport

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production [HttpClient] over `HttpURLConnection` (http and https alike):
 * zero runtime dependencies, so the SDK can never conflict with a host's
 * OkHttp version. Fixed-length streaming, explicit timeouts, and the response
 * body read fully as UTF-8 from the error stream on ≥400 (where
 * `getInputStream()` would throw) or the input stream otherwise.
 *
 * Redirects keep the platform default (same-scheme only); a cross-scheme
 * redirect surfaces as its 3xx status, which the transport treats as retryable.
 */
internal class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : HttpClient {

    @Throws(IOException::class)
    override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("Not an HTTP(S) URL: $url")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val text = bodyStream(connection, status)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun bodyStream(connection: HttpURLConnection, status: Int): InputStream? =
        if (status >= HTTP_ERROR_START) {
            connection.errorStream
        } else {
            try {
                connection.inputStream
            } catch (e: IOException) {
                connection.errorStream
            }
        }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 15_000
        const val HTTP_ERROR_START = 400
    }
}
