package com.zeotap.sdk.internal.consent.tcf

import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentOptions
import com.zeotap.sdk.ConsentSignals
import com.zeotap.sdk.ConsentSource
import com.zeotap.sdk.internal.consent.ConsentManager
import com.zeotap.sdk.internal.testing.FakeClock
import com.zeotap.sdk.internal.testing.FakeTcfDefaults
import com.zeotap.sdk.internal.testing.RecordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `TcfIntegration` timing cases of `tcf.test.ts`, remapped onto `IABTCF_*` preferences and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TcfAppsIntegrationTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val clock = FakeClock(1_700_000_000_000L)
    private val consent = ConsentManager(clock)
    private val defaults = FakeTcfDefaults()
    private var detected = 0
    private var listenerCalls = 0

    init {
        consent.onResolve { listenerCalls++ }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun start(cmpTimeoutMs: Long = 10_000, configure: ConsentOptions.Builder.() -> Unit = {}): TcfAppsIntegration {
        val options = ConsentOptions.Builder().cmpTimeoutMs(cmpTimeoutMs).apply(configure).build()
        val tcf = TcfAppsIntegration(defaults, consent, options, scope, clock, RecordingLogger()) { detected++ }
        tcf.start()
        scheduler.runCurrent()
        return tcf
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun bits(vararg ids: Int): String {
        val max = ids.maxOrNull() ?: 0
        return CharArray(max) { i -> if (i + 1 in ids) '1' else '0' }.concatToString()
    }

    private fun answered(vararg purposes: Int, vendor: Int? = TcfTranslator.ZEOTAP_GVL_ID, tcString: String = "TC-STRING") = TcData(
        cmpSdkId = 7,
        gdprApplies = true,
        tcString = tcString,
        purposeConsents = bits(*purposes),
        vendorConsents = vendor?.let { bits(it) },
    )

    private val pending = TcData(cmpSdkId = 7, gdprApplies = true, tcString = null, purposeConsents = null, vendorConsents = null)

    @Test
    fun `detects a CMP that appears after a few polls and starts observing`() {
        start()
        assertEquals(0, detected)
        advance(3_000)
        assertEquals(0, detected)

        defaults.set(pending, notify = false)
        advance(1_000)

        assertEquals(1, detected)
        assertEquals(1, defaults.observerCount)
        assertFalse(consent.isResolved())
    }

    @Test
    fun `gives up silently when no CMP appears within the budget`() {
        start(cmpTimeoutMs = 5_000)
        advance(20_000)
        assertEquals(0, detected)
        assertEquals(0, defaults.observerCount)
        assertFalse(consent.isResolved()) // the client's default timer decides
    }

    @Test
    fun `a present TC string resolves via offer with source cmp and the TC string`() {
        defaults.set(answered(1, 3, 4, 9))
        start()

        val resolution = assertNotNull(consent.getResolution())
        assertEquals(ConsentSource.CMP, resolution.source)
        assertEquals("TC-STRING", resolution.tcfString)
        assertEquals(true, resolution.gdprApplies)
        assertEquals(clock.nowMs, resolution.ts)
        assertEquals(ConsentSignals.uniform(true), resolution.signals)
        assertEquals(ConsentInput.Tcf("TC-STRING"), consent.getConsent())
        assertEquals(1, detected)
        assertEquals(1, defaults.observerCount) // stays attached for re-resolution
    }

    @Test
    fun `translation honours the consent options`() {
        defaults.set(answered(1, 3, 4, vendor = null))
        start { checkVendorConsent(true) }
        // No vendor map: track (vendor-checked) and identify (always vendor-gated) are denied; cookieSync still follows [1,3,4].
        assertEquals(ConsentSignals(track = false, identify = false, cookieSync = true), consent.getSignals())
    }

    @Test
    fun `gdprApplies false grants everything without a TC string and stops observing`() {
        defaults.set(answered(1).copy(gdprApplies = false))
        start()

        val resolution = assertNotNull(consent.getResolution())
        assertEquals(ConsentSignals.uniform(true), resolution.signals)
        assertEquals(false, resolution.gdprApplies)
        assertNull(resolution.tcfString)
        assertNull(consent.getConsent())
        assertEquals(0, defaults.observerCount)

        advance(30_000) // no ping backstop fires afterwards
        assertEquals(1, listenerCalls)
    }

    @Test
    fun `a present CMP without an answer keeps waiting, then resolves and can re-resolve`() {
        defaults.set(pending)
        start()
        assertEquals(1, detected)
        assertFalse(consent.isResolved())

        defaults.set(answered(1, 3, 4))
        assertFalse(consent.isResolved()) // debounced
        advance(250)
        assertEquals(true, consent.getSignals()?.track)

        // A later consent change (revoke) re-resolves through the same observer.
        defaults.set(answered().copy(purposeConsents = ""))
        advance(250)
        assertEquals(false, consent.getSignals()?.track)
        assertEquals(2, listenerCalls)

        advance(60_000) // the ping backstop was cancelled by the answer
        assertEquals(2, listenerCalls)
    }

    @Test
    fun `rapid preference changes coalesce into one evaluation`() {
        defaults.set(pending)
        start()
        defaults.set(answered(1, 3, 4))
        advance(100)
        defaults.set(answered(1, 3, 4))
        advance(100)
        defaults.set(answered(1, 3, 4, 9))
        advance(249)
        assertFalse(consent.isResolved())
        advance(1)
        assertEquals(1, listenerCalls)
        assertEquals(true, consent.getSignals()?.identify)
    }

    @Test
    fun `a CMP that never answers is denied by the ping backstop when GDPR is unknown`() {
        defaults.set(TcData(cmpSdkId = 7, gdprApplies = null, tcString = null, purposeConsents = null, vendorConsents = null))
        start(cmpTimeoutMs = 10_000)
        advance(9_999)
        assertFalse(consent.isResolved())
        advance(1)

        val resolution = assertNotNull(consent.getResolution())
        assertEquals(ConsentSource.CMP, resolution.source)
        assertEquals(ConsentSignals.uniform(false), resolution.signals)
        assertNull(resolution.gdprApplies)
        assertNull(resolution.tcfString)
    }

    @Test
    fun `a CMP that never answers is denied by the ping backstop when GDPR applies`() {
        defaults.set(pending)
        start(cmpTimeoutMs = 10_000)
        advance(10_000)
        assertEquals(ConsentSignals.uniform(false), consent.getSignals())
        assertEquals(true, consent.getResolution()?.gdprApplies)
    }

    @Test
    fun `the ping backstop grants when GDPR turned out not to apply`() {
        defaults.set(TcData(cmpSdkId = 7, gdprApplies = null, tcString = null, purposeConsents = null, vendorConsents = null))
        start(cmpTimeoutMs = 10_000)
        advance(5_000)
        defaults.set(pending.copy(gdprApplies = false), notify = false) // a change the observer missed
        advance(5_000)
        assertEquals(ConsentSignals.uniform(true), consent.getSignals())
        assertEquals(false, consent.getResolution()?.gdprApplies)
    }

    @Test
    fun `the ping backstop is armed once and does not reset on further silent changes`() {
        defaults.set(pending)
        start(cmpTimeoutMs = 10_000)
        advance(6_000)
        defaults.set(pending.copy(cmpSdkId = 8)) // still no answer; must not re-arm the timer
        advance(3_999)
        assertFalse(consent.isResolved())
        advance(1)
        assertTrue(consent.isResolved())
    }

    @Test
    fun `a manual setConsent beats a later CMP resolution`() {
        defaults.set(pending)
        start()
        consent.setConsent(ConsentInput.Parsed(analytics = false))
        defaults.set(answered(1, 3, 4))
        advance(250)

        assertEquals(ConsentSource.MANUAL, consent.getResolution()?.source)
        assertEquals(false, consent.getSignals()?.track)
    }

    @Test
    fun `stop cancels polling`() {
        val tcf = start()
        advance(1_000)
        tcf.stop()
        defaults.set(pending, notify = false)
        advance(5_000)
        assertEquals(0, detected)
    }

    @Test
    fun `stop cancels the ping backstop and closes the observer`() {
        defaults.set(pending)
        val tcf = start()
        assertEquals(1, defaults.observerCount)
        tcf.stop()
        assertEquals(0, defaults.observerCount)
        advance(20_000)
        assertFalse(consent.isResolved())
    }
}
