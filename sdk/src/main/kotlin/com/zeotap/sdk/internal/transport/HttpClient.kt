package com.zeotap.sdk.internal.transport

import java.io.IOException

internal data class HttpResponse(val status: Int, val body: String)

/**
 * Minimal blocking HTTP POST. The production implementation is
 * `HttpsURLConnection` (zero runtime deps, no OkHttp version conflicts with
 * hosts); tests script responses. Connection/read failures throw
 * [IOException], which the transport classifies as retryable.
 */
internal interface HttpClient {
    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse
}
