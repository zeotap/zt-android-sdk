package com.zeotap.sdk.sample

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
// Every click is preceded by scrollTo(): all three sample layouts are ScrollViews,
// and Espresso refuses to click a view less than 90% visible. Without it the suite
// depended on each button happening to be above the fold, so ADDING A ROW to
// activity_main.xml (increment 251's btn-set-ad-id) failed all seven tests on the
// API 23 emulator with `PerformException: Error performing 'single click'`. A
// layout edit must not be able to do that.
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.zeotap.sdk.Zeotap
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Real-process instrumentation: the sample app + the real SDK against an
 * in-process MockWebServer standing in for ingest. Covers what Robolectric
 * cannot: the durable queue surviving an SDK teardown/reload, the background
 * flush on Home, the TCF preferences path, and the request shape on a device.
 */
@RunWith(AndroidJUnit4::class)
class SdkFlowTest {
    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Zeotap.shutdownForTesting()
        server = MockWebServer().apply { start() }
        context.getSharedPreferences("zeotap_sdk", Context.MODE_PRIVATE).edit().clear().commit()
        // The sample app's own launch config persists too; without this every test
        // after optInGating… inherited require=true and waited out the CMP timeout.
        context.getSharedPreferences(SampleConfig.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("zeotap_sdk_events.db")
    }

    @After
    fun tearDown() {
        Zeotap.shutdownForTesting()
        server.shutdown()
    }

    private fun launch(vararg extras: Pair<String, Any>): ActivityScenario<MainActivity> {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(SampleConfig.KEY_API_HOST, server.url("/").toString().trimEnd('/'))
            putExtra(SampleConfig.KEY_WRITE_KEY, "wk_instrumented")
            putExtra(SampleConfig.KEY_AUTO_LOAD, true)
            putExtra(SampleConfig.KEY_FLUSH_AT, 100)
            putExtra(SampleConfig.KEY_FLUSH_INTERVAL, 3_600_000L)
            for ((k, v) in extras) {
                when (v) {
                    is Boolean -> putExtra(k, v)
                    is String -> putExtra(k, v)
                    is Int -> putExtra(k, v)
                    is Long -> putExtra(k, v)
                }
            }
        }
        return ActivityScenario.launch(intent)
    }

    private fun ok() = MockResponse().setResponseCode(200).setBody("""{"success":true,"received":1}""")

    private fun batchOf(request: RecordedRequest): List<JSONObject> {
        val body = JSONObject(request.body.readUtf8())
        val batch = body.getJSONArray("batch")
        return (0 until batch.length()).map { batch.getJSONObject(it) }
    }

    @Test
    fun trackFlushesBatchWithMobileContextAndHeaders() {
        server.enqueue(ok())
        launch().use {
            onView(withId(R.id.btn_track)).perform(scrollTo(), click())
            onView(withId(R.id.btn_flush)).perform(scrollTo(), click())
        }
        val request = server.takeRequest(10, TimeUnit.SECONDS)
        assertNotNull("no batch reached ingest", request)
        request!!
        assertEquals("/v1/batch", request.path)
        assertEquals("wk_instrumented", request.getHeader("X-Write-Key"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        val events = batchOf(request)
        assertEquals(1, events.size)
        val ev = events[0]
        assertEquals("track", ev.getString("type"))
        assertEquals("Sample Track", ev.getString("event"))
        assertEquals(ev.getString("timestamp"), ev.getString("sentAt"))
        val ctx = ev.getJSONObject("context")
        assertEquals("@zeotap/android-sdk", ctx.getJSONObject("library").getString("name"))
        assertEquals("android", ctx.getJSONObject("device").getString("type"))
        assertEquals("Android", ctx.getJSONObject("os").getString("name"))
        assertEquals(context.packageName, ctx.getJSONObject("app").getString("namespace"))
        assertFalse("device.id must never be collected", ctx.getJSONObject("device").has("id"))
        assertTrue(ctx.has("locale") && ctx.has("timezone") && ctx.has("screen") && ctx.has("network"))
    }

    @Test
    fun identitiesAreHashedAndAttached() {
        server.enqueue(ok())
        launch().use {
            onView(withId(R.id.btn_consent_accept)).perform(scrollTo(), click())
            // replaceText sets the field without the keyboard; the field has no pre-fill.
            onView(withId(R.id.input_email)).perform(scrollTo(), replaceText("Shopper@Example.com"))
            onView(withId(R.id.btn_set_identities)).perform(scrollTo(), click())
            onView(withId(R.id.btn_track)).perform(scrollTo(), click())
            onView(withId(R.id.btn_flush)).perform(scrollTo(), click())
        }
        val request = server.takeRequest(10, TimeUnit.SECONDS)!!
        val ctx = batchOf(request)[0].getJSONObject("context")
        val identities = ctx.getJSONObject("identities")
        val email = identities.getJSONObject("email")
        assertEquals(64, email.getString("sha256_lowercase").length)
        assertEquals(40, email.getString("sha1_uppercase").length)
        assertTrue(identities.has("cellno") && identities.has("cellno_e164"))
        assertEquals("fp-sample", identities.getString("fpuid"))
        assertFalse("raw email must never leave the device", request.body.toString().contains("Shopper@Example.com"))
        val consent = ctx.getJSONObject("consent")
        assertTrue(consent.getBoolean("analytics"))
    }

    @Test
    fun queuedEventsSurviveSdkTeardownAndResendWithSameMessageIds() {
        // Ingest down: retries exhaust, rows are RELEASED, not dropped.
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
        var firstIds: List<String> = emptyList()
        launch().use {
            onView(withId(R.id.btn_track_probe)).perform(scrollTo(), click())
            onView(withId(R.id.btn_flush)).perform(scrollTo(), click())
            val failed = server.takeRequest(20, TimeUnit.SECONDS)
            assertNotNull(failed)
            firstIds = batchOf(failed!!).map { it.getString("messageId") }
            // Drain the remaining retry attempts (500/1000/2000 ms backoff).
            repeat(3) { server.takeRequest(10, TimeUnit.SECONDS) }
        }
        // Simulate process death: tear the singleton down, relaunch, ingest is back.
        Zeotap.shutdownForTesting()
        server.enqueue(ok())
        launch().use {
            onView(withId(R.id.btn_flush)).perform(scrollTo(), click())
        }
        val resent = server.takeRequest(20, TimeUnit.SECONDS)
        assertNotNull("released rows were not resent after relaunch", resent)
        val ids = batchOf(resent!!).map { it.getString("messageId") }
        assertEquals(firstIds, ids)
    }

    @Test
    fun backgroundingFlushesTheQueue() {
        server.enqueue(ok())
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        launch().use {
            onView(withId(R.id.btn_screen)).perform(scrollTo(), click())
            device.pressHome()
            val request = server.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull("background did not flush", request)
            assertEquals("screen", batchOf(request!!)[0].getString("type"))
        }
    }

    @Test
    fun optInGatingBuffersUntilConsentAndDropsOnReject() {
        server.enqueue(ok())
        launch(SampleConfig.KEY_CONSENT_REQUIRE to true, SampleConfig.KEY_CMP to "none").use {
            onView(withId(R.id.btn_track)).perform(scrollTo(), click())
            onView(withId(R.id.btn_flush)).perform(scrollTo(), click())
            assertNull("gated event must not be sent before consent", server.takeRequest(2, TimeUnit.SECONDS))
            onView(withId(R.id.btn_consent_accept)).perform(scrollTo(), click())
            onView(withId(R.id.btn_flush)).perform(scrollTo(), click())
            val request = server.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("Sample Track", batchOf(request!!)[0].getString("event"))
            assertEquals("manual", Zeotap.getConsentState().source?.name?.lowercase())
        }
    }

    @Test
    fun tcfPreferencesResolveConsentWithoutHostCalls() {
        server.enqueue(ok())
        // CMP answered before launch: purposes 1,3,4,9 + vendor 1469, gdprApplies=1.
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE).edit()
            .putInt("IABTCF_CmpSdkID", 300)
            .putInt("IABTCF_gdprApplies", 1)
            .putString("IABTCF_TCString", "CQAbcInstrumented")
            .putString("IABTCF_PurposeConsents", TcfStubActivity.purposeBits(1, 3, 4, 9))
            .putString("IABTCF_VendorConsents", TcfStubActivity.vendorBits(1469))
            .commit()
        launch(SampleConfig.KEY_CONSENT_REQUIRE to true, SampleConfig.KEY_CMP to "auto").use {
            // Poll: detection + evaluation happen on the SDK thread.
            var resolved = false
            for (i in 0 until 50) {
                if (Zeotap.getConsentState().resolved) { resolved = true; break }
                Thread.sleep(100)
            }
            assertTrue("TCF prefs did not resolve consent", resolved)
            val state = Zeotap.getConsentState()
            assertEquals("cmp", state.source?.name?.lowercase())
            assertTrue(state.signals!!.track && state.signals!!.identify)
            onView(withId(R.id.btn_track)).perform(scrollTo(), click())
            onView(withId(R.id.btn_flush)).perform(scrollTo(), click())
            val request = server.takeRequest(10, TimeUnit.SECONDS)!!
            val consent = batchOf(request)[0].getJSONObject("context").getJSONObject("consent")
            assertEquals("CQAbcInstrumented", consent.getString("tcfString"))
        }
    }

    @Test
    /**
     * Installed then Opened, in that order, across however many batches carry them. The
     * network monitor's onAvailable fires on registration and forces a flush that can land
     * between the two enqueues, so on API 34 the pair arrived as two one-event batches — both
     * delivered, in order, each once. The contract is order and delivery, not the batch boundary.
     */
    fun lifecycleEventsFireOnFreshInstall() {
        repeat(3) { server.enqueue(ok()) }
        launch(SampleConfig.KEY_LIFECYCLE to true).use {
            onView(withId(R.id.btn_flush)).perform(scrollTo(), click())
            val names = mutableListOf<String>()
            while (names.size < 2) {
                val request = server.takeRequest(10, TimeUnit.SECONDS) ?: break
                names += batchOf(request).map { it.optString("event") }
            }
            assertEquals(listOf("Application Installed", "Application Opened"), names)
        }
    }
}
