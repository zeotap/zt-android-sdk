package com.zeotap.sdk.internal.context

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class DefaultContextCollectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeProvider(private val info: AdvertisingIdProvider.Info?) : AdvertisingIdProvider {
        var calls = 0

        override fun fetch(context: Context): AdvertisingIdProvider.Info? {
            calls++
            return info
        }
    }

    private fun collector(enabled: Boolean = false, provider: AdvertisingIdProvider = FakeProvider(null)) =
        DefaultContextCollector(context, enabled, provider)

    private fun List<Pair<String, JsonElement>>.block(name: String): JsonObject = single { it.first == name }.second.jsonObject

    @Test
    fun `entries come in the contractual order`() {
        val keys = collector().collect().map { it.first }
        assertThat(keys).containsExactly("app", "device", "os", "network", "screen", "locale", "timezone", "userAgent").inOrder()
    }

    @Test
    fun `app block has name version build namespace`() {
        val app = collector().collect().block("app")
        assertThat(app.keys.toList()).containsExactly("name", "version", "build", "namespace").inOrder()
        assertThat(app["namespace"]!!.jsonPrimitive.content).isEqualTo(context.packageName)
        assertThat(app["version"]!!.jsonPrimitive.isString).isTrue()
        assertThat(app["build"]!!.jsonPrimitive.isString).isTrue()
        assertThat(app["name"]!!.jsonPrimitive.isString).isTrue()
    }

    @Test
    fun `device block without advertising id when disabled and provider untouched`() {
        val provider = FakeProvider(AdvertisingIdProvider.Info("abc-123", false))
        val device = collector(enabled = false, provider = provider).collect().block("device")
        assertThat(device.keys.toList()).containsExactly("manufacturer", "model", "type").inOrder()
        assertThat(device["type"]!!.jsonPrimitive.content).isEqualTo("android")
        assertThat(provider.calls).isEqualTo(0)
    }

    @Test
    fun `advertising id honoured when enabled`() {
        val provider = FakeProvider(AdvertisingIdProvider.Info("abc-123", false))
        val c = collector(enabled = true, provider = provider)
        val device = c.collect().block("device")
        assertThat(device.keys.toList())
            .containsExactly("manufacturer", "model", "type", "advertisingId", "adTrackingEnabled").inOrder()
        assertThat(device["advertisingId"]!!.jsonPrimitive.content).isEqualTo("abc-123")
        assertThat(device["adTrackingEnabled"]!!.jsonPrimitive.booleanOrNull).isTrue()

        // Re-read per payload, NOT cached in the device block: that is what lets a
        // host-supplied id take effect after the first event. Keeping Play
        // Services to one IPC is CachingAdvertisingIdProvider's job, not the
        // collector's — see AdvertisingIdProviderTest.
        c.collect()
        assertThat(provider.calls).isEqualTo(2)
    }

    @Test
    fun `a host-supplied id set AFTER the first payload reaches the next one`() {
        // The bug this pins: the device block used to be built `by lazy`, so an
        // id supplied after the first event was silently dropped on Android
        // while working on iOS — the same public API behaving differently per
        // platform.
        val manual = ManualAdvertisingIdProvider()
        val c = collector(
            enabled = true,
            provider = HostFirstAdvertisingIdProvider(manual, FakeProvider(null)),
        )

        assertThat(c.collect().block("device").containsKey("advertisingId")).isFalse()

        manual.set("late-gaid", limitAdTracking = false)
        val device = c.collect().block("device")
        assertThat(device["advertisingId"]!!.jsonPrimitive.content).isEqualTo("late-gaid")
        assertThat(device["adTrackingEnabled"]!!.jsonPrimitive.booleanOrNull).isTrue()
    }

    @Test
    fun `withdrawing a host-supplied id removes it from the next payload`() {
        val manual = ManualAdvertisingIdProvider()
        manual.set("gaid-1", limitAdTracking = false)
        val c = collector(
            enabled = true,
            provider = HostFirstAdvertisingIdProvider(manual, FakeProvider(null)),
        )
        assertThat(c.collect().block("device").containsKey("advertisingId")).isTrue()

        // limitAdTracking withholds the id but still reports the decision.
        manual.set(null, limitAdTracking = true)
        val device = c.collect().block("device")
        assertThat(device.containsKey("advertisingId")).isFalse()
        assertThat(device["adTrackingEnabled"]!!.jsonPrimitive.booleanOrNull).isFalse()
    }

    @Test
    fun `limit ad tracking suppresses the id`() {
        val device = collector(enabled = true, provider = FakeProvider(AdvertisingIdProvider.Info("abc-123", true)))
            .collect().block("device")
        assertThat(device.containsKey("advertisingId")).isFalse()
        assertThat(device["adTrackingEnabled"]!!.jsonPrimitive.booleanOrNull).isFalse()
    }

    @Test
    fun `zero uuid is suppressed`() {
        val device = collector(
            enabled = true,
            provider = FakeProvider(AdvertisingIdProvider.Info("00000000-0000-0000-0000-000000000000", false)),
        ).collect().block("device")
        assertThat(device.containsKey("advertisingId")).isFalse()
        assertThat(device["adTrackingEnabled"]!!.jsonPrimitive.booleanOrNull).isTrue()
    }

    @Test
    fun `null id is suppressed`() {
        val device = collector(enabled = true, provider = FakeProvider(AdvertisingIdProvider.Info(null, false)))
            .collect().block("device")
        assertThat(device.containsKey("advertisingId")).isFalse()
        assertThat(device["adTrackingEnabled"]!!.jsonPrimitive.booleanOrNull).isTrue()
    }

    @Test
    fun `provider returning null adds neither key`() {
        val device = collector(enabled = true, provider = FakeProvider(null)).collect().block("device")
        assertThat(device.keys.toList()).containsExactly("manufacturer", "model", "type").inOrder()
    }

    @Test
    fun `os block`() {
        val os = collector().collect().block("os")
        assertThat(os.keys.toList()).containsExactly("name", "version").inOrder()
        assertThat(os["name"]!!.jsonPrimitive.content).isEqualTo("Android")
        assertThat(os["version"]!!.jsonPrimitive.isString).isTrue()
    }

    @Test
    fun `screen block has integer pixels and a numeric density`() {
        val screen = collector().collect().block("screen")
        assertThat(screen.keys.toList()).containsExactly("width", "height", "density").inOrder()
        assertThat(screen["width"]!!.jsonPrimitive.intOrNull).isNotNull()
        assertThat(screen["height"]!!.jsonPrimitive.intOrNull).isNotNull()
        val density = screen["density"]!!.jsonPrimitive
        assertThat(density.isString).isFalse()
        assertThat(density.content.toDouble()).isGreaterThan(0.0)
    }

    @Test
    fun `locale timezone and userAgent are strings`() {
        val entries = collector().collect().toMap()
        assertThat((entries["locale"] as JsonPrimitive).isString).isTrue()
        assertThat((entries["locale"] as JsonPrimitive).content).contains("-")
        assertThat((entries["timezone"] as JsonPrimitive).isString).isTrue()
        assertThat((entries["timezone"] as JsonPrimitive).content).isNotEmpty()
        assertThat((entries["userAgent"] as JsonPrimitive).isString).isTrue()
    }

    @Test
    fun `network block has booleans and is refreshed on every collect`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = requireNotNull(cm.activeNetwork)
        val c = collector()

        val before = c.collect().block("network")
        assertThat(before.keys.toList()).containsAtLeast("wifi", "cellular").inOrder()
        assertThat(before["wifi"]!!.jsonPrimitive.booleanOrNull).isNotNull()
        assertThat(before["cellular"]!!.jsonPrimitive.booleanOrNull).isNotNull()

        val wifiCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(wifiCaps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(cm).setNetworkCapabilities(network, wifiCaps)
        val onWifi = c.collect().block("network")
        assertThat(onWifi["wifi"]!!.jsonPrimitive.booleanOrNull).isTrue()
        assertThat(onWifi["cellular"]!!.jsonPrimitive.booleanOrNull).isFalse()

        val cellCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(cellCaps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(cm).setNetworkCapabilities(network, cellCaps)
        val onCell = c.collect().block("network")
        assertThat(onCell["wifi"]!!.jsonPrimitive.booleanOrNull).isFalse()
        assertThat(onCell["cellular"]!!.jsonPrimitive.booleanOrNull).isTrue()
    }

    @Test
    fun `carrier is included when the operator name is known`() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setNetworkOperatorName("Test Carrier")
        val network = collector().collect().block("network")
        assertThat(network["carrier"]!!.jsonPrimitive.content).isEqualTo("Test Carrier")

        shadowOf(tm).setNetworkOperatorName("")
        assertThat(collector().collect().block("network").containsKey("carrier")).isFalse()
    }
}
