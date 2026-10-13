package com.zeotap.sdk

/**
 * Completion callback for the event and identity APIs. Mirrors the browser
 * SDK's `Callback = (err?: Error) => void`: invoked with `null` once the call
 * was enqueued (or applied), or with a [Throwable] when it was dropped — a
 * denied consent signal, a full pre-consent buffer, or an enqueue failure.
 *
 * Invoked on the SDK's own thread, never the main thread. Hosts touching UI
 * must post to their own executor.
 */
public fun interface Callback {
    public fun invoke(error: Throwable?)
}
