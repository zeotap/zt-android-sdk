package com.zeotap.sdk.internal.consent.tcf

import com.zeotap.sdk.ConsentOptions
import com.zeotap.sdk.ConsentSignals

/**
 * TCF purpose/vendor consents → SDK signals — port of `sdk/js/src/tcf.ts` `translateTCData`, fed by
 * the IAB TCF-for-Apps bit strings (`'0'`/`'1'`, position i ↔ id i+1).
 */
internal object TcfTranslator {
    /** Zeotap's IAB Global Vendor List id. */
    const val ZEOTAP_GVL_ID = 1469

    /** Legacy Zeotap GVL id — checked until all clients migrate. */
    const val ZEOTAP_GVL_OLD_ID = 301

    /** Default purpose sets (either group must be fully consented). */
    val DEFAULT_TRACKING_PURPOSES: List<List<Int>> = listOf(listOf(1, 3, 4), listOf(1, 5, 6))
    val DEFAULT_IDENTIFYING_PURPOSES: List<Int> = listOf(1, 9)
    val DEFAULT_COOKIE_SYNC_PURPOSES: List<List<Int>> = listOf(listOf(1, 3, 4), listOf(1, 5, 6))

    /** `null` (key absent) → null; `""` → empty map; otherwise `{i+1: bits[i] == '1'}`. */
    fun bitStringToConsents(bits: String?): Map<Int, Boolean>? {
        if (bits == null) return null
        val out = LinkedHashMap<Int, Boolean>(bits.length)
        for (i in bits.indices) out[i + 1] = bits[i] == '1'
        return out
    }

    /**
     * - track: configured purposes (all required) or either default group; plus Zeotap vendor consent
     *   when `checkVendorConsent`.
     * - identify: ALWAYS requires Zeotap vendor consent, plus purposes (default [1,9]).
     * - cookieSync: configured purposes or either default group.
     * No purpose map at all → everything denied.
     */
    fun translate(purposeConsents: Map<Int, Boolean>?, vendorConsents: Map<Int, Boolean>?, options: ConsentOptions): ConsentSignals {
        val consents = purposeConsents ?: return ConsentSignals.uniform(false)
        val vendorOk = hasZeotapVendorConsent(vendorConsents)

        val track = when {
            options.checkVendorConsent && !vendorOk -> false
            options.purposesForTracking.isNotEmpty() -> every(consents, options.purposesForTracking)
            else -> anyGroup(consents, DEFAULT_TRACKING_PURPOSES)
        }

        val identifyPurposes = options.purposesForIdentifying.ifEmpty { DEFAULT_IDENTIFYING_PURPOSES }
        val identify = vendorOk && every(consents, identifyPurposes)

        val cookieSync = if (options.purposesForCookieSync.isNotEmpty()) {
            every(consents, options.purposesForCookieSync)
        } else {
            anyGroup(consents, DEFAULT_COOKIE_SYNC_PURPOSES)
        }

        return ConsentSignals(track = track, identify = identify, cookieSync = cookieSync)
    }

    fun translate(tcData: TcData, options: ConsentOptions): ConsentSignals =
        translate(bitStringToConsents(tcData.purposeConsents), bitStringToConsents(tcData.vendorConsents), options)

    private fun hasZeotapVendorConsent(vendorConsents: Map<Int, Boolean>?): Boolean =
        vendorConsents != null && (vendorConsents[ZEOTAP_GVL_ID] == true || vendorConsents[ZEOTAP_GVL_OLD_ID] == true)

    private fun every(consents: Map<Int, Boolean>, purposes: List<Int>): Boolean = purposes.all { consents[it] == true }

    private fun anyGroup(consents: Map<Int, Boolean>, groups: List<List<Int>>): Boolean = groups.any { every(consents, it) }
}
