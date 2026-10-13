package com.zeotap.sdk.internal.lifecycle

import com.zeotap.sdk.Storage
import com.zeotap.sdk.internal.storage.Keys

/**
 * Computes the opt-in application lifecycle events (Segment-compatible names
 * and properties) from the version/build persisted under `Keys.appVersion` /
 * `Keys.appBuild`. Pure: the engine turns each [Event] into a consent-gated
 * `track()` call.
 */
internal class LifecycleEvents(
    private val keys: Keys,
    private val storage: Storage,
    private val currentVersion: String,
    private val currentBuild: String,
) {
    /** One lifecycle event; [properties] keep insertion order for a stable payload. */
    data class Event(val name: String, val properties: LinkedHashMap<String, Any?>)

    /**
     * On process start: `Application Installed` (nothing stored) or
     * `Application Updated` (stored version/build differ), then always
     * `Application Opened {from_background:false}`; persists the current
     * version/build.
     */
    fun onLaunch(): List<Event> {
        val previousVersion = storage.get(keys.appVersion)
        val previousBuild = storage.get(keys.appBuild)
        val events = ArrayList<Event>(2)
        if (previousVersion == null && previousBuild == null) {
            events.add(Event(INSTALLED, linkedMapOf("version" to currentVersion, "build" to currentBuild)))
        } else if (previousVersion != currentVersion || previousBuild != currentBuild) {
            events.add(
                Event(
                    UPDATED,
                    linkedMapOf(
                        "version" to currentVersion,
                        "build" to currentBuild,
                        "previous_version" to previousVersion,
                        "previous_build" to previousBuild,
                    ),
                ),
            )
        }
        events.add(opened(fromBackground = false))
        storage.set(keys.appVersion, currentVersion)
        storage.set(keys.appBuild, currentBuild)
        return events
    }

    /** App returned to the foreground after a reported background. */
    fun onForeground(): Event = opened(fromBackground = true)

    /** App moved to the background; no properties. */
    fun onBackground(): Event = Event(BACKGROUNDED, LinkedHashMap())

    private fun opened(fromBackground: Boolean): Event = Event(
        OPENED,
        linkedMapOf("from_background" to fromBackground, "version" to currentVersion, "build" to currentBuild),
    )

    companion object {
        const val INSTALLED: String = "Application Installed"
        const val UPDATED: String = "Application Updated"
        const val OPENED: String = "Application Opened"
        const val BACKGROUNDED: String = "Application Backgrounded"
    }
}
