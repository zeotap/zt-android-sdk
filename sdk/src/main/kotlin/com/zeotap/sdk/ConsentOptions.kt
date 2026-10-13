package com.zeotap.sdk

/**
 * Consent behaviour configuration — same names and defaults as the browser
 * SDK's `ConsentOptions` (`client.ts`). With the defaults, events flow
 * immediately and consent is merely attached to payloads. Gating activates
 * when [require] is true or [default] is [ConsentDefault.DENIED].
 */
public class ConsentOptions private constructor(
    /** Opt-in mode: buffer events until consent resolves, then replay or drop. */
    public val require: Boolean,
    /** Resolution applied when no source answers within [cmpTimeoutMs]. */
    public val default: ConsentDefault,
    /** How long to wait for a consent source before resolving with [default]. */
    public val cmpTimeoutMs: Long,
    /** Max events buffered while consent is unresolved; beyond this the newest is dropped. */
    public val maxBufferedEvents: Int,
    /** TCF-for-Apps integration. Resolved default: AUTO when [require], else NONE. */
    public val cmp: CmpMode,
    /** TCF purposes that must ALL be consented for tracking. Empty = either [1,3,4] or [1,5,6]. */
    public val purposesForTracking: List<Int>,
    /** TCF purposes for identify (always plus Zeotap vendor consent). Empty = [1,9]. */
    public val purposesForIdentifying: List<Int>,
    /** TCF purposes feeding the persisted `cookieSync` signal. Empty = either [1,3,4] or [1,5,6]. */
    public val purposesForCookieSync: List<Int>,
    /** Also require Zeotap vendor consent (GVL 1469 / legacy 301) for tracking. */
    public val checkVendorConsent: Boolean,
    /** Persist resolved consent across launches. */
    public val persist: Boolean,
    /** Persisted consent TTL in days. */
    public val expiryDays: Int,
) {
    public class Builder {
        private var require: Boolean = false
        private var default: ConsentDefault = ConsentDefault.GRANTED
        private var cmpTimeoutMs: Long = DEFAULT_CMP_TIMEOUT_MS
        private var maxBufferedEvents: Int = DEFAULT_MAX_BUFFERED_EVENTS
        private var cmp: CmpMode? = null
        private var purposesForTracking: List<Int> = emptyList()
        private var purposesForIdentifying: List<Int> = emptyList()
        private var purposesForCookieSync: List<Int> = emptyList()
        private var checkVendorConsent: Boolean = false
        private var persist: Boolean = true
        private var expiryDays: Int = DEFAULT_EXPIRY_DAYS

        public fun require(value: Boolean): Builder = apply { require = value }
        public fun default(value: ConsentDefault): Builder = apply { default = value }
        public fun cmpTimeoutMs(value: Long): Builder = apply {
            require(value > 0) { "[SDK] consent.cmpTimeoutMs must be > 0" }
            cmpTimeoutMs = value
        }
        public fun maxBufferedEvents(value: Int): Builder = apply {
            require(value >= 0) { "[SDK] consent.maxBufferedEvents must be >= 0" }
            maxBufferedEvents = value
        }
        public fun cmp(value: CmpMode): Builder = apply { cmp = value }
        public fun purposesForTracking(value: List<Int>): Builder = apply { purposesForTracking = value.toList() }
        public fun purposesForIdentifying(value: List<Int>): Builder = apply { purposesForIdentifying = value.toList() }
        public fun purposesForCookieSync(value: List<Int>): Builder = apply { purposesForCookieSync = value.toList() }
        public fun checkVendorConsent(value: Boolean): Builder = apply { checkVendorConsent = value }
        public fun persist(value: Boolean): Builder = apply { persist = value }
        public fun expiryDays(value: Int): Builder = apply {
            require(value > 0) { "[SDK] consent.expiryDays must be > 0" }
            expiryDays = value
        }

        public fun build(): ConsentOptions = ConsentOptions(
            require = require,
            default = default,
            cmpTimeoutMs = cmpTimeoutMs,
            maxBufferedEvents = maxBufferedEvents,
            cmp = cmp ?: if (require) CmpMode.AUTO else CmpMode.NONE,
            purposesForTracking = purposesForTracking,
            purposesForIdentifying = purposesForIdentifying,
            purposesForCookieSync = purposesForCookieSync,
            checkVendorConsent = checkVendorConsent,
            persist = persist,
            expiryDays = expiryDays,
        )
    }

    /** Whether consent gating is active (opt-in mode or opt-out-by-default). */
    public val gatingActive: Boolean get() = require || default == ConsentDefault.DENIED

    public companion object {
        public const val DEFAULT_CMP_TIMEOUT_MS: Long = 10_000L
        public const val DEFAULT_MAX_BUFFERED_EVENTS: Int = 10
        public const val DEFAULT_EXPIRY_DAYS: Int = 365

        @JvmStatic
        public fun default(): ConsentOptions = Builder().build()
    }
}
