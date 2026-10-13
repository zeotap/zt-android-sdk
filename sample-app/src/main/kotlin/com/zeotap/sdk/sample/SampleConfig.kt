package com.zeotap.sdk.sample

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle

/**
 * Sample-app configuration: persisted in its own SharedPreferences and
 * overridable by launch-intent extras so instrumentation, Maestro and the
 * device-farm harness start the app deterministically:
 *
 * ```
 * adb shell am start -n com.zeotap.sdk.sample/.MainActivity \
 *   --es apiHost http://10.0.2.2:8090 --es writeKey wk_... \
 *   --ez consentRequire true --es consentDefault denied --ez autoLoad true --ez lifecycleEvents true
 * ```
 */
class SampleConfig private constructor(private val prefs: SharedPreferences) {
    var apiHost: String
        get() = prefs.getString(KEY_API_HOST, "http://10.0.2.2:8090") ?: ""
        set(v) = prefs.edit().putString(KEY_API_HOST, v).apply()
    var writeKey: String
        get() = prefs.getString(KEY_WRITE_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_WRITE_KEY, v).apply()
    var namespace: String
        get() = prefs.getString(KEY_NAMESPACE, "zeotap") ?: "zeotap"
        set(v) = prefs.edit().putString(KEY_NAMESPACE, v).apply()
    var consentRequire: Boolean
        get() = prefs.getBoolean(KEY_CONSENT_REQUIRE, false)
        set(v) = prefs.edit().putBoolean(KEY_CONSENT_REQUIRE, v).apply()
    /** "granted" | "denied" */
    var consentDefault: String
        get() = prefs.getString(KEY_CONSENT_DEFAULT, "granted") ?: "granted"
        set(v) = prefs.edit().putString(KEY_CONSENT_DEFAULT, v).apply()
    /** "auto" | "none" | "" (SDK default) */
    var cmp: String
        get() = prefs.getString(KEY_CMP, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CMP, v).apply()
    var cmpTimeoutMs: Long
        get() = prefs.getLong(KEY_CMP_TIMEOUT, 10_000L)
        set(v) = prefs.edit().putLong(KEY_CMP_TIMEOUT, v).apply()
    var flushAt: Int
        get() = prefs.getInt(KEY_FLUSH_AT, 10)
        set(v) = prefs.edit().putInt(KEY_FLUSH_AT, v).apply()
    var flushIntervalMs: Long
        get() = prefs.getLong(KEY_FLUSH_INTERVAL, 5_000L)
        set(v) = prefs.edit().putLong(KEY_FLUSH_INTERVAL, v).apply()
    var lifecycleEvents: Boolean
        get() = prefs.getBoolean(KEY_LIFECYCLE, false)
        set(v) = prefs.edit().putBoolean(KEY_LIFECYCLE, v).apply()
    var advertisingId: Boolean
        get() = prefs.getBoolean(KEY_AD_ID, false)
        set(v) = prefs.edit().putBoolean(KEY_AD_ID, v).apply()
    var autoLoad: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LOAD, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_LOAD, v).apply()

    /** Apply launch-intent extras (each optional) over the persisted values. */
    fun applyIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        extras.getString(KEY_API_HOST)?.let { apiHost = it }
        extras.getString(KEY_WRITE_KEY)?.let { writeKey = it }
        extras.getString(KEY_NAMESPACE)?.let { namespace = it }
        extras.boolOrNull(KEY_CONSENT_REQUIRE)?.let { consentRequire = it }
        extras.getString(KEY_CONSENT_DEFAULT)?.let { consentDefault = it }
        extras.getString(KEY_CMP)?.let { cmp = it }
        extras.longOrNull(KEY_CMP_TIMEOUT)?.let { cmpTimeoutMs = it }
        extras.longOrNull(KEY_FLUSH_AT)?.let { flushAt = it.toInt() }
        extras.longOrNull(KEY_FLUSH_INTERVAL)?.let { flushIntervalMs = it }
        extras.boolOrNull(KEY_LIFECYCLE)?.let { lifecycleEvents = it }
        extras.boolOrNull(KEY_AD_ID)?.let { advertisingId = it }
        extras.boolOrNull(KEY_AUTO_LOAD)?.let { autoLoad = it }
    }

    /*
     * Extras are read by VALUE TYPE, not by the type we wish they had.
     *
     * A launcher chooses the extra's type for us: `am start --ei` sends an Int,
     * `--el` a Long, `--es` a String, and Maestro's `launchApp: arguments:` picks
     * per YAML value (a quoted number becomes a String). `Bundle.getLong()` on an
     * Int extra does not convert — it returns the DEFAULT, silently. That reads as
     * "the flow's flushInterval override did nothing", which is how a
     * kill/relaunch test flushes early and then passes for the wrong reason. Same
     * trap for `getBoolean()` against a String "true".
     */
    private fun Bundle.longOrNull(key: String): Long? = when (val value = get(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun Bundle.boolOrNull(key: String): Boolean? = when (val value = get(key)) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> null
    }

    companion object {
        const val KEY_API_HOST = "apiHost"
        const val KEY_WRITE_KEY = "writeKey"
        const val KEY_NAMESPACE = "namespace"
        const val KEY_CONSENT_REQUIRE = "consentRequire"
        const val KEY_CONSENT_DEFAULT = "consentDefault"
        const val KEY_CMP = "cmp"
        const val KEY_CMP_TIMEOUT = "cmpTimeoutMs"
        const val KEY_FLUSH_AT = "flushAt"
        const val KEY_FLUSH_INTERVAL = "flushIntervalMs"
        const val KEY_LIFECYCLE = "lifecycleEvents"
        const val KEY_AD_ID = "advertisingId"
        const val KEY_AUTO_LOAD = "autoLoad"

        /**
         * What `btn-set-ad-id` supplies. Kept byte-identical in the iOS sample
         * (`SampleConfig.swift`), the React Native example (`src/config.ts`) and
         * `e2e/helpers/mobile/expectations.ts`, which asserts it reaches the webhook —
         * so one flow proves the same public API on all three platforms.
         *
         * Deliberately not the all-zero UUID: that is the value the platforms return for
         * "limit ad tracking", and the SDK suppresses it. Asserting on it would pass for
         * the wrong reason.
         */
        const val SAMPLE_ADVERTISING_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

        /**
         * Where every setting above PERSISTS between launches. A test that sets one
         * (say `require`) leaves it for the next test unless that test clears this
         * file — which is how five of SdkFlowTest's seven tests failed on their first
         * device run, each inheriting opt-in gating from the one before.
         */
        const val PREFS_NAME = "zeotap_sample_config"

        fun get(context: Context): SampleConfig =
            SampleConfig(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
