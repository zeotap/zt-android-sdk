package com.zeotap.sdk.internal.consent

import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentSignals
import com.zeotap.sdk.ConsentSource
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A resolved consent decision (`consent-state.ts` `ResolvedConsent`). `ts` is epoch milliseconds. */
internal data class ResolvedConsent(
    val signals: ConsentSignals,
    /** The host-provided input, when the source supplied one. Rides on `context.consent`. */
    val input: ConsentInput?,
    /** Raw TC string when CMP-sourced or supplied via [ConsentInput.Tcf]. */
    val tcfString: String?,
    val gdprApplies: Boolean?,
    val source: ConsentSource,
    val ts: Long,
)

/**
 * Pure consent-signal derivation — port of `consent-state.ts` `resolveSignals` and the cookie-sync
 * gate from `cookie-sync.ts` — plus the wire (JSON) form of a [ConsentInput].
 */
internal object ConsentState {
    /**
     * Parsed booleans: `track` requires `analytics != false` (absent grants), `identify` follows `track`.
     * `{tcfString}` / `{usPrivacy}`: track and identify granted (server-side enforcement model).
     * `cookieSync` is ALWAYS [consentPermitsSync] so this layer and the pixel gate cannot drift apart.
     */
    fun resolveSignals(input: ConsentInput): ConsentSignals {
        val cookieSync = consentPermitsSync(input)
        return when (input) {
            is ConsentInput.Tcf, is ConsentInput.UsPrivacy -> ConsentSignals(track = true, identify = true, cookieSync = cookieSync)
            is ConsentInput.Parsed -> {
                val track = input.analytics != false
                ConsentSignals(track = track, identify = track, cookieSync = cookieSync)
            }
        }
    }

    /** Whether cookie sync may run at all (`cookie-sync.ts` `consentPermitsSync`). */
    fun consentPermitsSync(input: ConsentInput?): Boolean = when (input) {
        null -> false
        is ConsentInput.Tcf -> input.tcfString.isNotEmpty()
        is ConsentInput.UsPrivacy -> usPrivacyPermitsSync(input.usPrivacy)
        is ConsentInput.Parsed -> input.advertising == true || input.marketing == true
    }

    /**
     * IAB US Privacy: `[version][notice][opt-out of sale][LSPA]`, e.g. `1YNN`. The third character
     * is the opt-out ('Y' = no sync). Short strings fail safe.
     */
    fun usPrivacyPermitsSync(s: String): Boolean = s.length >= 3 && s[2].uppercaseChar() != 'Y'

    /** `{"tcfString":..}` | `{"usPrivacy":..}` | non-null parsed booleans in order analytics, marketing, advertising, functional. */
    fun toJson(input: ConsentInput): JsonObject = when (input) {
        is ConsentInput.Tcf -> buildJsonObject { put("tcfString", input.tcfString) }
        is ConsentInput.UsPrivacy -> buildJsonObject { put("usPrivacy", input.usPrivacy) }
        is ConsentInput.Parsed -> buildJsonObject {
            input.analytics?.let { put("analytics", it) }
            input.marketing?.let { put("marketing", it) }
            input.advertising?.let { put("advertising", it) }
            input.functional?.let { put("functional", it) }
        }
    }

    /** Inverse of [toJson]: a `tcfString` key wins, then `usPrivacy`, else parsed booleans (missing → null). */
    fun fromJson(obj: JsonObject): ConsentInput? = when {
        obj.containsKey("tcfString") -> ConsentInput.Tcf(obj.stringOrEmpty("tcfString"))
        obj.containsKey("usPrivacy") -> ConsentInput.UsPrivacy(obj.stringOrEmpty("usPrivacy"))
        else -> ConsentInput.Parsed(
            analytics = obj.boolOrNull("analytics"),
            marketing = obj.boolOrNull("marketing"),
            advertising = obj.boolOrNull("advertising"),
            functional = obj.boolOrNull("functional"),
        )
    }

    /** `{"track":..,"identify":..,"cookieSync":..}` — key order is contractual for the persisted record. */
    fun signalsToJson(signals: ConsentSignals): JsonObject = buildJsonObject {
        put("track", signals.track)
        put("identify", signals.identify)
        put("cookieSync", signals.cookieSync)
    }

    /** Missing or non-boolean members read as denied. */
    fun signalsFromJson(obj: JsonObject): ConsentSignals = ConsentSignals(
        track = obj.boolOrNull("track") ?: false,
        identify = obj.boolOrNull("identify") ?: false,
        cookieSync = obj.boolOrNull("cookieSync") ?: false,
    )

    private fun JsonObject.boolOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.stringOrEmpty(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return if (primitive is JsonNull) "" else primitive.content
    }
}
