package com.zeotap.sdk.internal.lifecycle

/**
 * Process-level foreground/background signal (the web SDK's
 * `visibilitychange`/`pagehide`). Implemented over
 * `Application.registerActivityLifecycleCallbacks` with a started-activity
 * counter and a short debounce — the same algorithm as ProcessLifecycleOwner,
 * without pulling androidx.lifecycle/startup into consumers.
 */
internal interface AppLifecycle {
    interface Listener {
        fun onForeground()
        fun onBackground()
    }

    fun start(listener: Listener)
    fun stop()
}

/** Connectivity-regained signal; the SDK flushes and resets its backoff on it. */
internal interface NetworkMonitor {
    fun start(onAvailable: () -> Unit)
    fun stop()
}
