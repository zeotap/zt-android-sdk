package com.zeotap.sdk.internal.consent

import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentSignals
import com.zeotap.sdk.ConsentSource
import com.zeotap.sdk.internal.testing.FakeClock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors `consent.test.ts` and the resolution-machine half of `consent-state.test.ts`. */
class ConsentManagerTest {
    private val clock = FakeClock(1_700_000_000_000L)
    private val manager = ConsentManager(clock)

    private fun offer(
        signals: ConsentSignals,
        source: ConsentSource,
        ts: Long,
        tcfString: String? = null,
        gdprApplies: Boolean? = null,
        input: ConsentInput? = null,
    ) = manager.offer(ResolvedConsent(signals, input, tcfString, gdprApplies, source, ts))

    @Test
    fun `starts unresolved`() {
        assertFalse(manager.isResolved())
        assertNull(manager.getResolution())
        assertNull(manager.getSignals())
        assertNull(manager.getConsent())
    }

    @Test
    fun `setConsent stores each input form and stamps the clock`() {
        manager.setConsent(ConsentInput.Tcf("CPXxRfAPXxRfAAfKAB"))
        assertEquals(ConsentInput.Tcf("CPXxRfAPXxRfAAfKAB"), manager.getConsent())
        assertEquals("CPXxRfAPXxRfAAfKAB", manager.getResolution()?.tcfString)
        assertEquals(1_700_000_000_000L, manager.getResolution()?.ts)

        clock.advance(5)
        manager.setConsent(ConsentInput.UsPrivacy("1YNN"))
        assertEquals(ConsentInput.UsPrivacy("1YNN"), manager.getConsent())
        assertNull(manager.getResolution()?.tcfString)
        assertEquals(1_700_000_000_005L, manager.getResolution()?.ts)

        manager.setConsent(ConsentInput.Parsed(analytics = true, marketing = false))
        assertEquals(ConsentInput.Parsed(analytics = true, marketing = false), manager.getConsent())
    }

    @Test
    fun `setConsent replaces the previous state entirely`() {
        manager.setConsent(ConsentInput.Tcf("CPXxRfAPXxRfAAfKAB"))
        manager.setConsent(ConsentInput.Parsed(analytics = true, functional = true))
        val state = assertNotNull(manager.getConsent())
        assertEquals(ConsentInput.Parsed(analytics = true, functional = true), state)
        assertNull(manager.getResolution()?.tcfString)
    }

    @Test
    fun `setConsent resolves as manual and fires listeners`() {
        var calls = 0
        manager.onResolve { calls++ }
        manager.setConsent(ConsentInput.Parsed(analytics = true, advertising = true))
        assertTrue(manager.isResolved())
        assertEquals(ConsentSource.MANUAL, manager.getResolution()?.source)
        assertEquals(ConsentSignals.uniform(true), manager.getSignals())
        assertEquals(1, calls)
    }

    @Test
    fun `a weaker source cannot replace a stronger one`() {
        manager.setConsent(ConsentInput.Parsed(analytics = false))
        assertFalse(offer(ConsentSignals.uniform(true), ConsentSource.STORAGE, ts = 1))
        assertFalse(offer(ConsentSignals.uniform(true), ConsentSource.CMP, ts = 2))
        assertFalse(offer(ConsentSignals.uniform(true), ConsentSource.DEFAULT, ts = 3))
        assertEquals(false, manager.getSignals()?.track)
        assertEquals(ConsentSource.MANUAL, manager.getResolution()?.source)
    }

    @Test
    fun `cmp overrides storage and cmp re-resolves cmp`() {
        assertTrue(offer(ConsentSignals.uniform(true), ConsentSource.STORAGE, ts = 1))
        assertTrue(offer(ConsentSignals.uniform(false), ConsentSource.CMP, ts = 2))
        assertEquals(false, manager.getSignals()?.track)
        assertTrue(offer(ConsentSignals.uniform(true), ConsentSource.CMP, ts = 3))
        assertEquals(true, manager.getSignals()?.track)
        assertFalse(offer(ConsentSignals.uniform(false), ConsentSource.STORAGE, ts = 4))
    }

    @Test
    fun `manual setConsent replaces a cmp resolution`() {
        offer(ConsentSignals.uniform(true), ConsentSource.CMP, ts = 1)
        manager.setConsent(ConsentInput.Parsed(analytics = false))
        assertEquals(ConsentSource.MANUAL, manager.getResolution()?.source)
        assertEquals(false, manager.getSignals()?.track)
    }

    @Test
    fun `resolveDefault only fires while unresolved`() {
        manager.resolveDefault(false)
        assertEquals(ConsentSource.DEFAULT, manager.getResolution()?.source)
        assertEquals(ConsentSignals.uniform(false), manager.getSignals())
        assertNull(manager.getConsent())

        val other = ConsentManager(clock)
        other.setConsent(ConsentInput.Parsed(analytics = true))
        other.resolveDefault(false)
        assertEquals(ConsentSource.MANUAL, other.getResolution()?.source)
        assertEquals(true, other.getSignals()?.track)
    }

    @Test
    fun `storage beats default but carries its own ts`() {
        manager.resolveDefault(false)
        assertTrue(offer(ConsentSignals.uniform(true), ConsentSource.STORAGE, ts = 1_699_999_995_000L))
        assertEquals(ConsentSource.STORAGE, manager.getResolution()?.source)
        assertEquals(1_699_999_995_000L, manager.getResolution()?.ts)
    }

    @Test
    fun `clear returns to unresolved but keeps listeners registered`() {
        var calls = 0
        manager.onResolve { calls++ }
        manager.setConsent(ConsentInput.Parsed(analytics = true))
        manager.clear()
        assertFalse(manager.isResolved())
        assertNull(manager.getConsent())
        manager.setConsent(ConsentInput.Parsed(analytics = true))
        assertEquals(2, calls)
    }

    @Test
    fun `a CMP resolution without input surfaces its TC string as consent`() {
        offer(ConsentSignals.uniform(true), ConsentSource.CMP, ts = 1, tcfString = "CO-CMP", gdprApplies = true)
        assertEquals(ConsentInput.Tcf("CO-CMP"), manager.getConsent())
        assertEquals(true, manager.getResolution()?.gdprApplies)
    }

    @Test
    fun `a CMP resolution with no TC string has no consent input`() {
        offer(ConsentSignals.uniform(true), ConsentSource.CMP, ts = 1, gdprApplies = false)
        assertNull(manager.getConsent())
        offer(ConsentSignals.uniform(true), ConsentSource.CMP, ts = 2, tcfString = "")
        assertNull(manager.getConsent())
    }

    @Test
    fun `a hydrated resolution carries its original input`() {
        offer(ConsentSignals.uniform(true), ConsentSource.STORAGE, ts = 1, input = ConsentInput.Parsed(advertising = true))
        assertEquals(ConsentInput.Parsed(advertising = true), manager.getConsent())
    }

    @Test
    fun `listeners receive every accepted resolution including re-resolutions`() {
        val seen = mutableListOf<ConsentSource>()
        manager.onResolve { seen.add(it.source) }
        offer(ConsentSignals.uniform(true), ConsentSource.STORAGE, ts = 1)
        offer(ConsentSignals.uniform(true), ConsentSource.CMP, ts = 2)
        offer(ConsentSignals.uniform(false), ConsentSource.CMP, ts = 3)
        offer(ConsentSignals.uniform(true), ConsentSource.DEFAULT, ts = 4)
        assertEquals(listOf(ConsentSource.STORAGE, ConsentSource.CMP, ConsentSource.CMP), seen)
    }
}
