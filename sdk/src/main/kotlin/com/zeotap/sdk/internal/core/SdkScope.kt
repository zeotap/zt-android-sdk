package com.zeotap.sdk.internal.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors

/**
 * The SDK's execution model: ONE single-threaded dispatcher on which every
 * public call is posted in order (the browser SDK's single JS thread), plus an
 * IO dispatcher for the network. Tests inject a `StandardTestDispatcher` for
 * virtual time.
 */
internal class SdkScope(
    val dispatcher: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    private val onUncaught: (Throwable) -> Unit = {},
) {
    private val job = SupervisorJob()
    val scope: CoroutineScope = CoroutineScope(
        job + dispatcher + CoroutineExceptionHandler { _, t -> onUncaught(t) },
    )

    fun close() {
        scope.cancel()
        (dispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    companion object {
        fun production(onUncaught: (Throwable) -> Unit): SdkScope {
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "zeotap-sdk").apply { isDaemon = true }
            }
            return SdkScope(executor.asCoroutineDispatcher(), Dispatchers.IO, onUncaught)
        }
    }
}
