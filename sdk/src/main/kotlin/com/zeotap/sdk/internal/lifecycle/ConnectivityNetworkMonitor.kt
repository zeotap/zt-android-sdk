package com.zeotap.sdk.internal.lifecycle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import com.zeotap.sdk.Logger
import com.zeotap.sdk.internal.util.NoopLogger

/**
 * [NetworkMonitor] over `ConnectivityManager.NetworkCallback`:
 * `registerDefaultNetworkCallback` on API 24+, `registerNetworkCallback` with
 * an empty [NetworkRequest] on API 23. `onAvailable` → [start]'s callback,
 * on a ConnectivityManager thread; the engine re-posts to its own.
 *
 * Note the default-network callback fires once immediately for the network
 * that is already up — the engine's "flush + reset backoff" reaction to that
 * is harmless. Some OEM builds throw `SecurityException` /
 * `IllegalArgumentException` from registration despite the declared
 * permission; those are logged and the SDK simply runs without the signal.
 */
internal class ConnectivityNetworkMonitor(
    context: Context,
    private val logger: Logger = NoopLogger,
) : NetworkMonitor {
    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onAvailable: () -> Unit) {
        val manager = connectivity ?: return
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onAvailable()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(cb)
            } else {
                manager.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            }
            callback = cb
        } catch (e: SecurityException) {
            logger.warn("[SDK] Network monitor unavailable: ${e.message}")
        } catch (e: IllegalArgumentException) {
            logger.warn("[SDK] Network monitor unavailable: ${e.message}")
        }
    }

    override fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            connectivity?.unregisterNetworkCallback(cb)
        } catch (ignored: IllegalArgumentException) {
            // Already unregistered by the system (process teardown) — nothing to undo.
        }
    }
}
