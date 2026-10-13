package com.zeotap.sdk.internal.consent.tcf

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [TcfDefaults] over the app's default SharedPreferences file (`${packageName}_preferences`, the
 * PreferenceManager default), where IAB TCF-for-Apps CMP SDKs publish the `IABTCF_*` keys.
 */
internal class IabTcfPrefsReader(context: Context) : TcfDefaults {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    /** SharedPreferences holds listeners weakly; without a strong reference they vanish at the next GC. */
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun read(): TcData {
        val all: Map<String, *> = prefs.all
        return TcData(
            cmpSdkId = all.intValue(KEY_CMP_SDK_ID),
            gdprApplies = all.intValue(KEY_GDPR_APPLIES)?.let { it != 0 },
            tcString = all[KEY_TC_STRING] as? String,
            purposeConsents = all[KEY_PURPOSE_CONSENTS] as? String,
            vendorConsents = all[KEY_VENDOR_CONSENTS] as? String,
        )
    }

    override fun observe(onChange: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // key == null: the whole file was cleared (API 30+).
            if (key == null || key.startsWith(KEY_PREFIX)) onChange()
        }
        listeners.add(listener)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            listeners.remove(listener)
        }
    }

    /** IAB mandates Int, but tolerate a CMP that wrote the number as a String. */
    private fun Map<String, *>.intValue(key: String): Int? =
        (this[key] as? Number)?.toInt() ?: (this[key] as? String)?.trim()?.toIntOrNull()

    private companion object {
        const val KEY_PREFIX = "IABTCF_"
        const val KEY_CMP_SDK_ID = "IABTCF_CmpSdkID"
        const val KEY_GDPR_APPLIES = "IABTCF_gdprApplies"
        const val KEY_TC_STRING = "IABTCF_TCString"
        const val KEY_PURPOSE_CONSENTS = "IABTCF_PurposeConsents"
        const val KEY_VENDOR_CONSENTS = "IABTCF_VendorConsents"
    }
}
