package com.zeotap.sdk.internal.consent.tcf

/**
 * The IAB TCF-for-Apps data a CMP SDK writes to the app's default
 * SharedPreferences (`IABTCF_*`). Bit strings are '0'/'1' characters indexed
 * from 1 (position i ↔ id i+1); `null` = key absent.
 */
internal data class TcData(
    val cmpSdkId: Int?,
    val gdprApplies: Boolean?,
    val tcString: String?,
    val purposeConsents: String?,
    val vendorConsents: String?,
) {
    /** ⇔ `typeof window.__tcfapi === "function"` on the web. */
    val cmpPresent: Boolean get() = cmpSdkId != null || gdprApplies != null || tcString != null

    companion object {
        val EMPTY = TcData(null, null, null, null, null)
    }
}

/** Read + observe the `IABTCF_*` preferences. Injectable so tests drive the timing model. */
internal interface TcfDefaults {
    fun read(): TcData

    /** Register a change listener; the returned handle unregisters it. */
    fun observe(onChange: () -> Unit): AutoCloseable
}
