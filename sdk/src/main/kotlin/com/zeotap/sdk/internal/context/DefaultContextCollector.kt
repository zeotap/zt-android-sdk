package com.zeotap.sdk.internal.context

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.zeotap.sdk.internal.json.JsonMapper
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.TimeZone

/**
 * Production [ContextCollector]. Static blocks (`app`, `device`, `os`,
 * `screen`, `locale`, `timezone`, `userAgent`) are computed once, lazily and
 * thread-safely, on first [collect]; `network` is re-read on every call.
 *
 * Privacy: no ANDROID_ID, no device name, no Wi-Fi IP. The advertising id is
 * fetched only when [collectAdvertisingId] is true, and omitted (with
 * `adTrackingEnabled:false`) when the user limited ad tracking or Play
 * Services returned the all-zero placeholder.
 *
 * The first [collect] may block on the advertising-id IPC, so the engine calls
 * it off the main thread (its own SDK thread / IO dispatcher).
 */
internal class DefaultContextCollector(
    context: Context,
    private val collectAdvertisingId: Boolean,
    private val advertisingId: AdvertisingIdProvider,
) : ContextCollector {
    private val app: Context = context.applicationContext

    private val appBlock: JsonObject by lazy { buildApp() }

    /**
     * Manufacturer/model/type only — they cannot change while the process lives.
     *
     * The advertising id is deliberately NOT in here. It used to be, as part of a
     * fully-lazy `device` block, which meant a host calling `setAdvertisingId`
     * after the first event was silently ignored: the block was already frozen.
     * iOS refreshes the advertising info per `collect()`, so the two SDKs
     * disagreed about the same public API. Now both re-read per payload, and the
     * expensive part — the Play Services IPC — is cached inside
     * `CachingAdvertisingIdProvider` instead of here.
     */
    private val staticDeviceBlock: JsonObject by lazy { buildStaticDevice() }
    private val osBlock: JsonObject by lazy {
        buildJsonObject {
            put("name", "Android")
            put("version", Build.VERSION.RELEASE ?: "")
        }
    }
    private val screenBlock: JsonObject by lazy { buildScreen() }
    private val locale: JsonElement by lazy { JsonPrimitive(Locale.getDefault().toLanguageTag()) }
    private val timezone: JsonElement by lazy { JsonPrimitive(TimeZone.getDefault().id) }
    private val userAgent: JsonElement by lazy { JsonPrimitive(System.getProperty("http.agent") ?: "") }

    override fun collect(): List<Pair<String, JsonElement>> = listOf(
        "app" to appBlock,
        "device" to buildDevice(),
        "os" to osBlock,
        "network" to buildNetwork(),
        "screen" to screenBlock,
        "locale" to locale,
        "timezone" to timezone,
        "userAgent" to userAgent,
    )

    @Suppress("DEPRECATION") // versionCode is the only source before API 28
    private fun buildApp(): JsonObject {
        val pm = app.packageManager
        val info = packageInfo(pm)
        val build = when {
            info == null -> ""
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode.toString()
            else -> info.versionCode.toString()
        }
        return buildJsonObject {
            put("name", app.applicationInfo.loadLabel(pm).toString())
            put("version", info?.versionName ?: "")
            put("build", build)
            put("namespace", app.packageName)
        }
    }

    @Suppress("DEPRECATION") // the PackageInfoFlags overload is API 33+
    private fun packageInfo(pm: PackageManager): PackageInfo? = try {
        pm.getPackageInfo(app.packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun buildStaticDevice(): JsonObject = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER ?: "")
        put("model", Build.MODEL ?: "")
        put("type", "android")
    }

    /**
     * Rebuilt per payload so a host-supplied id takes effect for subsequent
     * events. Cheap: the static half is cached and the provider's own caching
     * keeps Play Services to one call per process.
     */
    private fun buildDevice(): JsonObject = buildJsonObject {
        for ((key, value) in staticDeviceBlock) put(key, value)
        if (collectAdvertisingId) {
            val info = advertisingId.fetch(app)
            if (info != null) {
                val id = info.id
                if (!info.limitAdTracking && id != null && id != ZERO_UUID) put("advertisingId", id)
                put("adTrackingEnabled", !info.limitAdTracking)
            }
        }
    }

    private fun buildScreen(): JsonObject {
        val metrics = Resources.getSystem().displayMetrics
        return buildJsonObject {
            put("width", metrics.widthPixels)
            put("height", metrics.heightPixels)
            // Normalised like every other number (1.0 → 1, 2.625 → 2.625).
            put("density", JsonMapper.fromValue(metrics.density.toDouble()))
        }
    }

    private fun buildNetwork(): JsonObject {
        var wifi = false
        var cellular = false
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            try {
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            } catch (ignored: SecurityException) {
                // Some OEM builds throw despite ACCESS_NETWORK_STATE; report "unknown" as false/false.
            } catch (ignored: RuntimeException) {
                // Binder/DeadSystem hiccups — never fail an event over a context block.
            }
        }
        val carrier = (app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkOperatorName
        return buildJsonObject {
            put("wifi", wifi)
            put("cellular", cellular)
            if (!carrier.isNullOrBlank()) put("carrier", carrier)
        }
    }

    private companion object {
        /** What Play Services returns when the user opted out of ads personalisation. */
        const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"
    }
}
