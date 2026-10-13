package com.zeotap.sdk.internal.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting

/**
 * Process foreground/background detection over
 * `Application.registerActivityLifecycleCallbacks` — the ProcessLifecycleOwner
 * algorithm without the androidx.lifecycle/startup dependency:
 *
 *  - a started-activity counter; `onBackground()` fires when it stays at zero
 *    for [BACKGROUND_DEBOUNCE_MS] (an activity swap or a rotation stops one
 *    activity and starts the next within a few ms — no false background);
 *  - a stop caused by a configuration change never even arms the timer;
 *  - `onForeground()` fires when the counter leaves zero AFTER a background
 *    was reported. The initial launch is NOT reported here: the engine already
 *    emits `Application Opened {from_background:false}` from `start()`, so a
 *    tracker signal on the first activity start would double-count it (and
 *    hosts that call `load()` from an Activity are already in the foreground).
 *
 * Callbacks run on the main thread; the engine re-posts to its own thread.
 */
internal class AppLifecycleTracker(
    private val app: Application,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : AppLifecycle {
    private var listener: AppLifecycle.Listener? = null
    private var started = 0
    private var inBackground = false
    private var pendingBackground: Runnable? = null

    @VisibleForTesting
    internal val callbacks: Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
            cancelPending()
            if (started == 1 && inBackground) {
                inBackground = false
                listener?.onForeground()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            started = maxOf(0, started - 1)
            if (started == 0 && !activity.isChangingConfigurations) scheduleBackground()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun start(listener: AppLifecycle.Listener) {
        this.listener = listener
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    override fun stop() {
        app.unregisterActivityLifecycleCallbacks(callbacks)
        cancelPending()
        listener = null
    }

    private fun scheduleBackground() {
        cancelPending()
        val runnable = Runnable {
            pendingBackground = null
            if (started == 0 && !inBackground) {
                inBackground = true
                listener?.onBackground()
            }
        }
        pendingBackground = runnable
        handler.postDelayed(runnable, BACKGROUND_DEBOUNCE_MS)
    }

    private fun cancelPending() {
        pendingBackground?.let { handler.removeCallbacks(it) }
        pendingBackground = null
    }

    companion object {
        /** Same window ProcessLifecycleOwner uses to absorb activity transitions. */
        const val BACKGROUND_DEBOUNCE_MS: Long = 700L
    }
}
