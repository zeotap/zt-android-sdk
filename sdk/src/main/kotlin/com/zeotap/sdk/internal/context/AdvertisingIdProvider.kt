package com.zeotap.sdk.internal.context

import android.content.Context

/**
 * Source of the Google Advertising ID. Only consulted when the host opted in
 * via `Configuration.collectAdvertisingId`; the SDK never adds the Play
 * Services dependency itself.
 */
internal interface AdvertisingIdProvider {
    /** `id` is null when unavailable; `limitAdTracking` mirrors the user's opt-out switch. */
    data class Info(val id: String?, val limitAdTracking: Boolean)

    /** Blocking. MUST run off the main thread — Play Services answers over IPC. */
    fun fetch(context: Context): Info?
}

/**
 * A host-supplied advertising id, settable at any time after `load()`.
 *
 * Exists because the reflective provider below cannot serve every app: one that
 * resolves the GAID itself (or gets it from a mediation SDK, or deliberately
 * ships without `play-services-ads-identifier`) has the value already and no way
 * to hand it over. The legacy SDK's `setAdID` covered that case, and iOS covers
 * it with `ZTConfiguration.setAdvertisingId(_:trackingAuthorized:)`, so an
 * Android SDK without it made the React Native bridge asymmetric for a reason
 * that was an omission rather than a platform difference.
 *
 * Read once per payload on the SDK's own thread, so `@Volatile` is enough: a
 * value set now takes effect for subsequent events and never mid-payload.
 */
internal class ManualAdvertisingIdProvider : AdvertisingIdProvider {
    @Volatile
    private var info: AdvertisingIdProvider.Info? = null

    /** `null` id withdraws a previously supplied one. */
    fun set(id: String?, limitAdTracking: Boolean) {
        info = AdvertisingIdProvider.Info(id, limitAdTracking)
    }

    fun isSet(): Boolean = info != null

    override fun fetch(context: Context): AdvertisingIdProvider.Info? = info
}

/**
 * Prefers whatever the host supplied, falling back to [delegate].
 *
 * Host-wins is the right precedence: an app that took the trouble to call
 * `setAdvertisingId` knows something the reflective lookup does not (it may have
 * a mediation SDK's value, or a deliberate opt-out), and silently overriding it
 * with a Play Services answer would be the SDK second-guessing the integrator.
 */
internal class HostFirstAdvertisingIdProvider(
    private val manual: ManualAdvertisingIdProvider,
    private val delegate: AdvertisingIdProvider,
) : AdvertisingIdProvider {
    override fun fetch(context: Context): AdvertisingIdProvider.Info? =
        if (manual.isSet()) manual.fetch(context) else delegate.fetch(context)
}

/**
 * Memoises the first answer from [delegate], including a null one.
 *
 * The reflective lookup below is a blocking IPC to Play Services, so it must not
 * run once per event. Caching used to be implicit — `DefaultContextCollector`
 * built the whole `device` block `by lazy` — but that also froze a
 * HOST-SUPPLIED id, so `setAdvertisingId` after the first event silently did
 * nothing on Android while working on iOS. Separating the two concerns fixes
 * that: the collector now reads per payload (cheap), and "do not call Play
 * Services again" lives here.
 *
 * A null first answer is cached too, matching the previous behaviour: "no id
 * available" is a legitimate steady state (no Play Services, an outdated one, a
 * user opt-out), and retrying an IPC on every event to find that out again would
 * cost more than it could ever return. A host that CAN produce an id should
 * supply it via [ManualAdvertisingIdProvider], which is checked first and is not
 * cached.
 */
internal class CachingAdvertisingIdProvider(
    private val delegate: AdvertisingIdProvider,
) : AdvertisingIdProvider {
    @Volatile
    private var fetched = false

    @Volatile
    private var cached: AdvertisingIdProvider.Info? = null

    override fun fetch(context: Context): AdvertisingIdProvider.Info? {
        if (!fetched) {
            // Benign race: two threads may both fetch on the first payload. The
            // alternative is holding a lock across a blocking IPC, which is a
            // worse trade for a value that does not change.
            cached = delegate.fetch(context)
            fetched = true
        }
        return cached
    }
}

/**
 * Reflective bridge to `com.google.android.gms.ads.identifier.AdvertisingIdClient`
 * so hosts without `play-services-ads-identifier` on the classpath never hit a
 * `NoClassDefFoundError`. Any failure (class absent, Play Services missing or
 * outdated, IPC error, main-thread guard) yields null: no id is a valid state.
 *
 * Blocking; call it once off the main thread (the engine does so on its IO
 * dispatcher) and cache the result in the device context block.
 */
internal object ReflectiveAdvertisingIdProvider : AdvertisingIdProvider {
    private const val CLIENT_CLASS = "com.google.android.gms.ads.identifier.AdvertisingIdClient"

    override fun fetch(context: Context): AdvertisingIdProvider.Info? = try {
        val clientClass = Class.forName(CLIENT_CLASS)
        val info = clientClass.getMethod("getAdvertisingIdInfo", Context::class.java).invoke(null, context)
        if (info == null) {
            null
        } else {
            val id = info.javaClass.getMethod("getId").invoke(info) as? String
            val limited = info.javaClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean ?: false
            AdvertisingIdProvider.Info(id, limited)
        }
    } catch (t: Throwable) {
        null
    }
}
