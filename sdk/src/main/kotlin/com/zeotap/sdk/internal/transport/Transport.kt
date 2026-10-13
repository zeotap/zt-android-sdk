package com.zeotap.sdk.internal.transport

import com.zeotap.sdk.BatchResult
import com.zeotap.sdk.BuildConfig
import com.zeotap.sdk.Logger
import com.zeotap.sdk.internal.util.Clock
import com.zeotap.sdk.internal.util.Iso8601
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException

/** What one [Transport.send] ended in; the engine decides ack/release/drop from it. */
internal sealed class SendOutcome {
    /** 2xx — the batch is delivered; [result] carries ingest's per-event verdict. */
    data class Success(val result: BatchResult) : SendOutcome()

    /** Non-retryable 4xx (≠ 429): the batch is wrong, not the network — drop it. */
    data class Rejected(val status: Int, val body: String) : SendOutcome()

    /**
     * All attempts failed on 5xx / 429 / network. [lastStatus] is the last HTTP
     * status seen and [error] the last `IOException` seen — both may be present
     * when the attempts mixed the two failure kinds. The engine releases the rows.
     */
    data class Exhausted(val attempts: Int, val lastStatus: Int?, val error: Throwable?) : SendOutcome()
}

/**
 * Port of the web SDK's `transport.ts` `send()`: POST `{batch, sentAt}` to
 * `{apiHost}/v1/batch`, retry 5xx / 429 / network errors with 500 → 1000 →
 * 2000 ms backoff (4 attempts total), fail fast on any other 4xx. The body is
 * built ONCE per call so every retry carries identical bytes (same messageIds:
 * ingest is at-least-once with no dedupe).
 *
 * [sleep] is injectable so tests record delays instead of waiting.
 */
internal class Transport(
    private val apiHost: String,
    private val writeKey: String,
    private val nativeAppId: String,
    private val http: HttpClient,
    private val clock: Clock,
    private val logger: Logger,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val url: String = apiHost.trimEnd('/') + "/v1/batch"

    /** [payloads] are final, already-serialised event JSON objects. */
    suspend fun send(payloads: List<String>): SendOutcome {
        if (payloads.isEmpty()) return SendOutcome.Success(BatchResult(HTTP_OK, true, 0, 0, emptyList()))

        val body = buildBody(payloads).toByteArray(Charsets.UTF_8)
        val headers = buildHeaders()
        var lastStatus: Int? = null
        var lastError: Throwable? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                val response = http.post(url, headers, body)
                val status = response.status
                if (status in HTTP_OK..HTTP_2XX_END) {
                    logger.debug("[SDK] Sent ${payloads.size} events")
                    return SendOutcome.Success(parseResult(status, response.body, payloads.size))
                }
                if (status in HTTP_4XX_START..HTTP_4XX_END && status != HTTP_TOO_MANY_REQUESTS) {
                    logger.debug("[SDK] Ingest API error $status: ${response.body}")
                    return SendOutcome.Rejected(status, response.body)
                }
                lastStatus = status
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < MAX_RETRIES) {
                val delayMs = BASE_DELAY_MS shl attempt
                logger.debug("[SDK] Retry ${attempt + 1}/$MAX_RETRIES in ${delayMs}ms")
                sleep(delayMs)
            }
        }

        logger.error("[SDK] Failed to send after $MAX_RETRIES retries (last status $lastStatus)", lastError)
        return SendOutcome.Exhausted(MAX_RETRIES + 1, lastStatus, lastError)
    }

    private fun buildBody(payloads: List<String>): String =
        "{\"batch\":[" + payloads.joinToString(",") + "],\"sentAt\":\"" + Iso8601.format(clock.nowMs()) + "\"}"

    private fun buildHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["X-Write-Key"] = writeKey
        if (nativeAppId.isNotEmpty()) headers["X-Native-App-ID"] = nativeAppId
        headers["User-Agent"] = "${BuildConfig.SDK_NAME}/${BuildConfig.SDK_VERSION}"
        return headers
    }

    /**
     * Ingest answers 200 even on partial failure: `{success:false, received:n,
     * errors:["event 1: …"]}`. A missing/malformed body (204, proxies) reads as
     * full success — the status already said so.
     */
    private fun parseResult(status: Int, body: String, sent: Int): BatchResult {
        val obj = parseObject(body) ?: return BatchResult(status, true, sent, sent, emptyList())
        val success = (obj["success"] as? JsonPrimitive)?.booleanOrNull ?: true
        val received = (obj["received"] as? JsonPrimitive)?.intOrNull ?: sent
        val errors = (obj["errors"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        return BatchResult(status, success, received, sent, errors)
    }

    private fun parseObject(body: String): JsonObject? {
        if (body.isBlank()) return null
        return try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
    }

    companion object {
        const val MAX_RETRIES: Int = 3
        const val BASE_DELAY_MS: Long = 500L
        /** Payload-byte budget per reserved batch (ingest caps the request at 10 MB). */
        const val RESERVE_MAX_BYTES: Long = 4L * 1024 * 1024

        private const val HTTP_OK = 200
        private const val HTTP_2XX_END = 299
        private const val HTTP_4XX_START = 400
        private const val HTTP_4XX_END = 499
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
