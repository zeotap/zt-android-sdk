package com.zeotap.sdk.internal.consent

import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentSignals
import com.zeotap.sdk.ConsentSource
import com.zeotap.sdk.internal.util.Clock

/**
 * Consent resolution state machine — port of `sdk/js/src/consent.ts`. Unresolved until a source
 * answers; precedence MANUAL > CMP > STORAGE > DEFAULT — a weaker source never overwrites a stronger
 * one, a source may re-resolve itself. Listeners fire on every accepted (re-)resolution.
 */
internal class ConsentManager(private val clock: Clock) {
    private var resolution: ResolvedConsent? = null
    private val listeners = mutableListOf<(ResolvedConsent) -> Unit>()

    /** Host-supplied consent — source MANUAL, always wins. */
    fun setConsent(input: ConsentInput) {
        accept(
            ResolvedConsent(
                signals = ConsentState.resolveSignals(input),
                input = input,
                tcfString = (input as? ConsentInput.Tcf)?.tcfString,
                gdprApplies = null,
                source = ConsentSource.MANUAL,
                ts = clock.nowMs(),
            ),
        )
    }

    /** Offer a non-manual resolution; rejected (false) when a strictly stronger source already resolved. */
    fun offer(resolution: ResolvedConsent): Boolean {
        val current = this.resolution
        if (current != null && resolution.source.rank < current.source.rank) return false
        accept(resolution)
        return true
    }

    /** Resolve with the configured default; no-op once resolved. */
    fun resolveDefault(granted: Boolean) {
        if (resolution != null) return
        accept(
            ResolvedConsent(
                signals = ConsentSignals.uniform(granted),
                input = null,
                tcfString = null,
                gdprApplies = null,
                source = ConsentSource.DEFAULT,
                ts = clock.nowMs(),
            ),
        )
    }

    fun onResolve(listener: (ResolvedConsent) -> Unit) {
        listeners.add(listener)
    }

    fun isResolved(): Boolean = resolution != null

    fun getResolution(): ResolvedConsent? = resolution

    fun getSignals(): ConsentSignals? = resolution?.signals

    /**
     * The consent input for payload context: the host input when present, else a CMP-sourced TC string
     * surfaces as [ConsentInput.Tcf] so it rides on every event even though the host never called setConsent().
     */
    fun getConsent(): ConsentInput? {
        val current = resolution ?: return null
        return current.input ?: current.tcfString?.takeIf { it.isNotEmpty() }?.let { ConsentInput.Tcf(it) }
    }

    /** Back to unresolved; listeners stay registered. */
    fun clear() {
        resolution = null
    }

    private fun accept(resolution: ResolvedConsent) {
        this.resolution = resolution
        for (listener in listeners.toList()) listener(resolution)
    }
}
