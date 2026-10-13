package com.zeotap.sdk.internal.util

import android.util.Log
import com.zeotap.sdk.Logger

/**
 * Default log sink over `android.util.Log`, tag `ZeotapSDK`. Debug lines are
 * emitted only when [debugEnabled]; warnings and errors always are (a host
 * troubleshooting a missing event should not need a debug build to see why).
 */
internal class AndroidLogger(private val debugEnabled: Boolean) : Logger {
    override fun debug(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    override fun error(message: String, error: Throwable?) {
        if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
    }

    private companion object {
        const val TAG = "ZeotapSDK"
    }
}

/** Discards everything; the default for unit tests and for hosts that inject no logger. */
internal object NoopLogger : Logger {
    override fun debug(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}
