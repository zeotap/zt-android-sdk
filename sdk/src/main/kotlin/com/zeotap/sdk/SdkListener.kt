package com.zeotap.sdk

/**
 * Result of one `/v1/batch` delivery. Ingest answers 200 even on partial
 * failure (`{success:false, errors:[…]}`), which a status-code check would
 * read as success — so the SDK parses the body and reports it here.
 */
public data class BatchResult(
    /** HTTP status of the final attempt. */
    public val status: Int,
    /** `success` from the response body (true when absent on a 2xx). */
    public val success: Boolean,
    /** `received` from the response body. */
    public val received: Int,
    /** Number of events in the batch. */
    public val sent: Int,
    /** `errors[]` from the response body, e.g. `event 0: missing type`. */
    public val errors: List<String>,
)

/** Reason an event or identity update never reached the queue. */
public enum class DropReason { CONSENT_DENIED, BUFFER_FULL, INGEST_REJECTED, ENQUEUE_FAILED }

/**
 * Observability hook — a deliberate improvement over the browser SDK, which
 * only logs in debug mode. All methods have empty defaults; invoked on the SDK
 * thread.
 */
public interface SdkListener {
    /** A batch was accepted (2xx) — inspect [BatchResult.success] and [BatchResult.errors]. */
    public fun onBatchResult(result: BatchResult) {}

    /** A batch was rejected with a non-retryable 4xx and dropped, or retries were exhausted. */
    public fun onDeliveryFailure(status: Int?, attempts: Int, error: Throwable?) {}

    /** An event (or identity update) was dropped before delivery. */
    public fun onEventDropped(type: String, reason: DropReason, message: String) {}

    /** An unexpected internal error (storage, serialisation). */
    public fun onError(error: Throwable) {}
}
