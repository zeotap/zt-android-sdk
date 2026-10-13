package com.zeotap.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The PUBLIC surface, driven the way a customer drives it.
 *
 * Every other suite here reaches past this layer: `AnalyticsClientTest` and the
 * conformance runners build an `AnalyticsClient` over fakes through
 * `ClientHarness`, which is the right way to test the engine but means the three
 * types an app actually touches — `Zeotap`, `Analytics` and the `SdkFactory`
 * wiring behind them — were never instantiated at all. They sat at 0 % line
 * coverage while the engine sat near 100 %, and the overall Kover bound was
 * failing because of it.
 *
 * So this file is deliberately shallow and wide: it asserts the guards, the
 * lifecycle and that every public method reaches the engine without throwing,
 * and leaves what each method DOES to the suites that can control time and
 * transport. Nothing here asserts delivery — the facade runs on the SDK's real
 * dispatcher against a real SQLite queue, so anything asynchronous would be a
 * race. `flushAt` and `flushInterval` are set high for the same reason: no test
 * here should ever put a batch on the wire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZeotapFacadeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun config(build: Configuration.Builder.() -> Unit = {}): Configuration =
        Configuration.Builder("https://ingest.example")
            // Nothing may reach the network from a unit test: 10_000 events or an
            // hour, whichever the test does not do.
            .flushAt(10_000)
            .flushIntervalMs(3_600_000)
            .apply(build)
            .build()

    @After
    fun tearDown() {
        // The facade is a process-wide singleton holding timers, a lifecycle
        // observer and an open database; without this the next test inherits it.
        Zeotap.shutdownForTesting()
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    @Test
    fun `load returns a usable instance and marks the facade initialised`() {
        assertFalse(Zeotap.isInitialized())
        val analytics = Zeotap.load(context, "wk_test", config())
        assertTrue(Zeotap.isInitialized())
        assertSame(analytics, Zeotap.getInstance())
        // An anonymous id is minted during load, not lazily on the first event —
        // so an app can read it before sending anything.
        assertTrue(analytics.anonymousId.isNotEmpty())
        assertNull(analytics.userId)
    }

    @Test
    fun `load is idempotent — a second call returns the first instance`() {
        // A re-created Application or a re-entrant initialiser must not build a
        // second engine over the same database.
        val first = Zeotap.load(context, "wk_test", config())
        val second = Zeotap.load(context, "wk_other", config())
        assertSame(first, second)
    }

    @Test
    fun `a blank write key is refused with the browser SDK's message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Zeotap.load(context, "   ", config())
        }
        assertEquals("[SDK] writeKey is required", error.message)
        assertFalse(Zeotap.isInitialized())
    }

    @Test
    fun `shutdownForTesting returns the facade to its pre-load state`() {
        Zeotap.load(context, "wk_test", config())
        Zeotap.shutdownForTesting()
        assertFalse(Zeotap.isInitialized())
    }

    // ── the not-initialised guards ──────────────────────────────────────────

    @Test
    fun `calls before load throw with the method named, as on the web`() {
        val cases = mapOf<String, () -> Unit>(
            "track" to { Zeotap.track("Ev") },
            "screen" to { Zeotap.screen() },
            "identify" to { Zeotap.identify() },
            "group" to { Zeotap.group("g1") },
            "alias" to { Zeotap.alias("n1") },
            "setConsent" to { Zeotap.setConsent(ConsentInput.Parsed(analytics = true)) },
            "setUserIdentities" to { Zeotap.setUserIdentities(mapOf("email" to "a@b.com")) },
            "unsetUserIdentities" to { Zeotap.unsetUserIdentities() },
            "reset" to { Zeotap.reset() },
            "setAdvertisingId" to { Zeotap.setAdvertisingId("gaid-1") },
        )
        for ((method, call) in cases) {
            val error = assertFailsWith<NotInitializedException>("$method should refuse") { call() }
            assertEquals("[SDK] Not initialized. Call load() before $method().", error.message)
        }
    }

    @Test
    fun `getConsentState and flush are callable before load`() {
        // Web parity: `getConsentState()` answers rather than throwing, and
        // `flush()` is a silent no-op — an app's shutdown hook should not have to
        // know whether the SDK was ever loaded.
        assertFalse(Zeotap.getConsentState().resolved)
        Zeotap.flush()
    }

    // ── the surface reaches the engine ──────────────────────────────────────

    @Test
    fun `every event method is callable through the facade`() {
        Zeotap.load(context, "wk_test", config())
        Zeotap.track("Sample Track", mapOf("count" to 1))
        Zeotap.screen("Sample", "Home", mapOf("tab" to "main"))
        Zeotap.identify("user-42", mapOf("plan" to "pro"))
        Zeotap.group("acme", mapOf("plan" to "enterprise"))
        Zeotap.alias("aliased-1")
        Zeotap.setUserIdentities(mapOf("email" to "a@b.com", "cellno" to "91 9999999999"))
        Zeotap.unsetUserIdentities(listOf("email"))
        Zeotap.flush()
        // The assertion is the absence of a throw: what each call produces is
        // pinned by AnalyticsClientTest and the conformance runners, which can
        // control the clock and capture the transport.
    }

    @Test
    fun `consent and reset are callable through the facade`() {
        Zeotap.load(context, "wk_test", config())
        Zeotap.setConsent(ConsentInput.Parsed(analytics = true, marketing = false))
        Zeotap.identify("user-42")
        Zeotap.reset()
        // Deliberately NOT asserting the resulting state here. Both calls are
        // posted to the SDK's single-threaded dispatcher, so reading
        // `getConsentState()` or `anonymousId` on the test thread immediately
        // afterwards is a race — the first draft of this file did exactly that
        // and failed. What they DO is pinned where the dispatcher is
        // controllable: `AnalyticsClientTest` for reset's rotate-and-clear, and
        // ten `gating` conformance cases for `source = manual`.
    }

    @Test
    fun `setAdvertisingId is callable through both the instance and the facade`() {
        // Both arities, since @JvmOverloads generates them for Java hosts.
        val analytics = Zeotap.load(context, "wk_test", config { collectAdvertisingId(true) })
        analytics.setAdvertisingId("gaid-1")
        analytics.setAdvertisingId("gaid-1", limitAdTracking = true)
        Zeotap.setAdvertisingId(null)
        Zeotap.setAdvertisingId("gaid-2", limitAdTracking = false)
    }

    @Test
    fun `setAdvertisingId without collectAdvertisingId does not throw`() {
        // It warns and does nothing — the flag is the host's privacy switch and
        // one call must not override it. A throw would be worse: an app that
        // guards the call with the flag would still crash on the path where a
        // config change turned it off.
        Zeotap.load(context, "wk_test", config())
        Zeotap.setAdvertisingId("gaid-1")
    }

    @Test
    fun `listeners can be added and removed through the instance`() {
        val analytics = Zeotap.load(context, "wk_test", config())
        val listener = object : SdkListener {}
        analytics.addListener(listener)
        analytics.removeListener(listener)
    }

    // ── multi-instance ──────────────────────────────────────────────────────

    @Test
    fun `create builds an instance independent of the singleton`() {
        // Multi-brand hosts need a second engine; it must not become the one the
        // static facade forwards to.
        val standalone = Analytics.create(context, "wk_standalone", config { namespace("other") })
        assertFalse(Zeotap.isInitialized())
        assertTrue(standalone.anonymousId.isNotEmpty())
        standalone.shutdown()
    }

    @Test
    fun `create refuses a blank write key too`() {
        assertFailsWith<IllegalArgumentException> {
            Analytics.create(context, "", config())
        }
    }
}
