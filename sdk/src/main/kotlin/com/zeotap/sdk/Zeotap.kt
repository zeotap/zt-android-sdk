package com.zeotap.sdk

import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * Process-wide facade — the equivalent of the browser SDK's `window.zeotap`.
 *
 * ```kotlin
 * Zeotap.load(context, "wk_…", Configuration.Builder("https://events.zeotap.com").build())
 * Zeotap.track("Button Clicked", mapOf("label" to "signup"))
 * ```
 *
 * [load] is idempotent (a second call returns the existing instance). Every
 * other method throws [NotInitializedException] before [load] — web parity.
 */
public object Zeotap {
    @Volatile
    private var instance: Analytics? = null

    /** Initialise once. Later calls return the existing instance and ignore the new configuration. */
    @JvmStatic
    public fun load(context: Context, writeKey: String, configuration: Configuration): Analytics {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            return Analytics.create(context, writeKey, configuration).also { instance = it }
        }
    }

    @JvmStatic
    public fun isInitialized(): Boolean = instance != null

    /** The live instance. Throws [NotInitializedException] before [load]. */
    @JvmStatic
    public fun getInstance(): Analytics = instance ?: throw NotInitializedException("getInstance")

    private fun ready(method: String): Analytics = instance ?: throw NotInitializedException(method)

    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String? = null, traits: Map<String, Any?>? = null, callback: Callback? = null): Unit =
        ready("identify").identify(userId, traits, callback)

    @JvmStatic
    @JvmOverloads
    public fun track(event: String, properties: Map<String, Any?>? = null, callback: Callback? = null): Unit =
        ready("track").track(event, properties, callback)

    @JvmStatic
    @JvmOverloads
    public fun screen(
        name: String? = null,
        category: String? = null,
        properties: Map<String, Any?>? = null,
        callback: Callback? = null,
    ): Unit = ready("screen").screen(name, category, properties, callback)

    @JvmStatic
    @JvmOverloads
    public fun group(groupId: String, traits: Map<String, Any?>? = null, callback: Callback? = null): Unit =
        ready("group").group(groupId, traits, callback)

    @JvmStatic
    @JvmOverloads
    public fun alias(newId: String, previousId: String? = null, callback: Callback? = null): Unit =
        ready("alias").alias(newId, previousId, callback)

    @JvmStatic
    public fun setConsent(consent: ConsentInput): Unit = ready("setConsent").setConsent(consent)

    @JvmStatic
    @JvmOverloads
    public fun setUserIdentities(identities: Map<String, String?>, hashed: Boolean = false, callback: Callback? = null): Unit =
        ready("setUserIdentities").setUserIdentities(identities, hashed, callback)

    @JvmStatic
    @JvmOverloads
    public fun unsetUserIdentities(keys: List<String>? = null): Unit = ready("unsetUserIdentities").unsetUserIdentities(keys)

    /** Safe before [load]: returns the unresolved snapshot (web parity). */
    @JvmStatic
    public fun getConsentState(): ConsentStateSnapshot = instance?.getConsentState() ?: ConsentStateSnapshot.UNRESOLVED

    @JvmStatic
    public fun reset(): Unit = ready("reset").reset()

    /** Silent no-op before [load] (web parity). */
    @JvmStatic
    public fun flush() {
        instance?.flush()
    }

    /**
     * Supply the advertising id yourself — see [Analytics.setAdvertisingId].
     * Requires `Configuration.collectAdvertisingId = true`.
     */
    @JvmStatic
    @JvmOverloads
    public fun setAdvertisingId(id: String?, limitAdTracking: Boolean = false): Unit =
        ready("setAdvertisingId").setAdvertisingId(id, limitAdTracking)

    /** Tear down the singleton so the next [load] starts fresh. Tests only. */
    @VisibleForTesting
    @JvmStatic
    public fun shutdownForTesting() {
        synchronized(this) {
            instance?.shutdown()
            instance = null
        }
    }
}
