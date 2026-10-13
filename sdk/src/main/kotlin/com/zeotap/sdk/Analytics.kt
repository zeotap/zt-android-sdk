package com.zeotap.sdk

import android.content.Context
import com.zeotap.sdk.internal.core.AnalyticsClient
import com.zeotap.sdk.internal.core.SdkFactory

/**
 * The SDK client — the browser SDK's `AnalyticsClient` for Android. One
 * instance per write key. Most hosts use the [Zeotap] facade; multi-brand
 * hosts create instances directly with [Analytics.create].
 *
 * Every method is safe to call from any thread: work is posted to the SDK's
 * single dispatcher thread, which preserves call order (an event issued right
 * after [setUserIdentities] carries the identities). Callbacks fire on that
 * thread.
 *
 * Business logic (payload shape, consent gating, hashing, retry policy) is
 * byte-compatible with the browser SDK and pinned by `sdk/conformance`.
 */
public class Analytics internal constructor(private val client: AnalyticsClient) {

    /** Identify a user with traits. Persisting the user id is a consent-gated side effect. */
    @JvmOverloads
    public fun identify(userId: String? = null, traits: Map<String, Any?>? = null, callback: Callback? = null) {
        client.identify(userId, traits, callback)
    }

    /** Track a named event. An empty name is a silent no-op (web parity). */
    @JvmOverloads
    public fun track(event: String, properties: Map<String, Any?>? = null, callback: Callback? = null) {
        client.track(event, properties, callback)
    }

    /** Record a screen view. */
    @JvmOverloads
    public fun screen(
        name: String? = null,
        category: String? = null,
        properties: Map<String, Any?>? = null,
        callback: Callback? = null,
    ) {
        client.screen(category, name, properties, callback)
    }

    /** Associate the current user with a group. An empty groupId is a silent no-op. */
    @JvmOverloads
    public fun group(groupId: String, traits: Map<String, Any?>? = null, callback: Callback? = null) {
        client.group(groupId, traits, callback)
    }

    /** Alias a new user id to a previous one (defaults to the current anonymous id). */
    @JvmOverloads
    public fun alias(newId: String, previousId: String? = null, callback: Callback? = null) {
        client.alias(newId, previousId, callback)
    }

    /** Set consent (source `manual`, the strongest). Attached to `context.consent` on later events. */
    public fun setConsent(consent: ConsentInput) {
        client.setConsent(consent)
    }

    /**
     * Set PII identities. Raw values are normalised and hashed on-device
     * (SHA-256/SHA-1 — never sent raw); pass `hashed = true` for pre-hashed
     * legacy-key input. Attached to every later event as `context.identities`.
     * Gated by the identify consent signal.
     */
    @JvmOverloads
    public fun setUserIdentities(identities: Map<String, String?>, hashed: Boolean = false, callback: Callback? = null) {
        client.setUserIdentities(identities, hashed, callback)
    }

    /** Remove specific identity keys, or all when `keys` is null/empty. Never consent-gated. */
    @JvmOverloads
    public fun unsetUserIdentities(keys: List<String>? = null) {
        client.unsetUserIdentities(keys)
    }

    /** Read-only snapshot of the consent resolution. Callable from any thread. */
    public fun getConsentState(): ConsentStateSnapshot = client.getConsentState()

    /** Current anonymous id (for hybrid WebView / bridge use). */
    public val anonymousId: String get() = client.anonymousId()

    /** Current user id, if identified. */
    public val userId: String? get() = client.userId()

    /**
     * Logout: new anonymous id, drop user id, identities, queued and buffered
     * events, and consent (back to unresolved; the default timer re-arms).
     */
    public fun reset() {
        client.reset()
    }

    /** Send queued events now. */
    public fun flush() {
        client.flush()
    }

    /**
     * Supply the advertising id yourself, instead of letting the SDK resolve it.
     *
     * For an app that already has the GAID — from its own lookup, or a mediation
     * SDK — or that deliberately ships without `play-services-ads-identifier`.
     * Takes effect for subsequent events; pass `id = null` to withdraw one.
     * A host-supplied value always wins over the SDK's own lookup.
     *
     * **Requires `Configuration.collectAdvertisingId = true`.** Without it the
     * device block carries no advertising id at all and this call is inert — the
     * SDK logs a warning saying so rather than failing, because the flag is the
     * host's single switch for "may I collect this" and one call should not
     * override it. Same rule as iOS's `setAdvertisingId`.
     *
     * @param limitAdTracking the user's opt-out. When true the id is NOT sent and
     *   `adTrackingEnabled: false` is, which is the whole point of reporting it.
     */
    @JvmOverloads
    public fun setAdvertisingId(id: String?, limitAdTracking: Boolean = false) {
        client.setAdvertisingId(id, limitAdTracking)
    }

    public fun addListener(listener: SdkListener) {
        client.addListener(listener)
    }

    public fun removeListener(listener: SdkListener) {
        client.removeListener(listener)
    }

    /** Stop timers and observers and close the queue. The instance is unusable afterwards. */
    public fun shutdown() {
        client.shutdown()
    }

    public companion object {
        /**
         * Create an independent instance (multi-brand hosts). Throws
         * [IllegalArgumentException] on a blank write key (web: `[SDK] writeKey is required`).
         */
        @JvmStatic
        public fun create(context: Context, writeKey: String, configuration: Configuration): Analytics {
            require(writeKey.isNotBlank()) { "[SDK] writeKey is required" }
            return Analytics(SdkFactory.create(context.applicationContext, writeKey, configuration))
        }
    }
}
