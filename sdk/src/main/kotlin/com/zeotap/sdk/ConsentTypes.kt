package com.zeotap.sdk

/**
 * Consent input — exactly one of three mutually exclusive forms, mirroring the
 * browser SDK's `ConsentInput` union (`consent.ts`):
 *  - [Tcf]: a raw IAB TCF v2 consent string (the server derives categories),
 *  - [UsPrivacy]: a raw IAB US Privacy / CCPA string,
 *  - [Parsed]: pre-resolved category booleans from the host's own CMP.
 *
 * Serialised verbatim onto `context.consent` of every event.
 */
public sealed class ConsentInput {
    public data class Tcf(public val tcfString: String) : ConsentInput()

    public data class UsPrivacy(public val usPrivacy: String) : ConsentInput()

    /**
     * Only non-null members are serialised, in the canonical order
     * analytics, marketing, advertising, functional.
     */
    public data class Parsed(
        public val analytics: Boolean? = null,
        public val marketing: Boolean? = null,
        public val advertising: Boolean? = null,
        public val functional: Boolean? = null,
    ) : ConsentInput()
}

/** Per-action consent signals derived from a resolution (`consent-state.ts`). */
public data class ConsentSignals(
    /** Gates track/screen/group/alias events. */
    public val track: Boolean,
    /** Gates identify events and user-identity persistence. */
    public val identify: Boolean,
    /**
     * Browser cookie-sync gate. Mobile never cookie-syncs, but the signal is
     * part of the persisted `{ns}_consent` record and is kept byte-compatible.
     */
    public val cookieSync: Boolean,
) {
    public companion object {
        /** All-granted / all-denied signal sets (`uniformSignals`). */
        public fun uniform(granted: Boolean): ConsentSignals = ConsentSignals(granted, granted, granted)
    }
}

/** Where a resolution came from. Precedence: MANUAL > CMP > STORAGE > DEFAULT. */
public enum class ConsentSource(internal val rank: Int, internal val wire: String) {
    DEFAULT(0, "default"),
    STORAGE(1, "storage"),
    CMP(2, "cmp"),
    MANUAL(3, "manual");

    internal companion object {
        fun fromWire(value: String?): ConsentSource? = entries.firstOrNull { it.wire == value }
    }
}

/** Read-only snapshot of the consent resolution (see [Analytics.getConsentState]). */
public data class ConsentStateSnapshot(
    public val resolved: Boolean,
    public val signals: ConsentSignals? = null,
    public val source: ConsentSource? = null,
    public val gdprApplies: Boolean? = null,
) {
    public companion object {
        public val UNRESOLVED: ConsentStateSnapshot = ConsentStateSnapshot(resolved = false)
    }
}

/** Resolution applied when no consent source answers within `cmpTimeoutMs`. */
public enum class ConsentDefault { GRANTED, DENIED }

/** TCF CMP integration mode: read the IAB TCF-for-Apps `IABTCF_*` preferences, or don't. */
public enum class CmpMode { AUTO, NONE }

/**
 * Identity of a wrapping integration (e.g. the React Native bridge) that
 * installed the SDK. Attached to every event as `context.library.integration`
 * so the serving channel is observable from the traffic itself.
 */
public data class IntegrationMeta(public val name: String, public val version: String)
