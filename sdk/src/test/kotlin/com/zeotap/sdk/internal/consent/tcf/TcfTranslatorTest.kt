package com.zeotap.sdk.internal.consent.tcf

import com.zeotap.sdk.ConsentOptions
import com.zeotap.sdk.ConsentSignals
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors the `translateTCData` half of `tcf.test.ts`, driven through the IAB bit-string decoder. */
class TcfTranslatorTest {
    private val defaults = ConsentOptions.default()

    private fun purposes(vararg ids: Int): Map<Int, Boolean> = ids.associateWith { true }

    private fun vendors(vararg ids: Int): Map<Int, Boolean> = ids.associateWith { true }

    private fun options(block: ConsentOptions.Builder.() -> Unit) = ConsentOptions.Builder().apply(block).build()

    @Test
    fun `track is granted by either default purpose group`() {
        assertTrue(TcfTranslator.translate(purposes(1, 3, 4), null, defaults).track)
        assertTrue(TcfTranslator.translate(purposes(1, 5, 6), null, defaults).track)
        assertFalse(TcfTranslator.translate(purposes(1, 3), null, defaults).track)
    }

    @Test
    fun `custom purposesForTracking must all be consented`() {
        val consents = purposes(1, 2)
        assertTrue(TcfTranslator.translate(consents, null, options { purposesForTracking(listOf(1, 2)) }).track)
        assertFalse(TcfTranslator.translate(consents, null, options { purposesForTracking(listOf(1, 2, 3)) }).track)
    }

    @Test
    fun `an empty purposes list means use the defaults`() {
        assertTrue(TcfTranslator.translate(purposes(1, 3, 4), null, options { purposesForTracking(emptyList()) }).track)
    }

    @Test
    fun `checkVendorConsent requires the Zeotap vendor for track`() {
        val opts = options { checkVendorConsent(true) }
        assertFalse(TcfTranslator.translate(purposes(1, 3, 4), null, opts).track)
        assertFalse(TcfTranslator.translate(purposes(1, 3, 4), mapOf(TcfTranslator.ZEOTAP_GVL_ID to false), opts).track)
        assertTrue(TcfTranslator.translate(purposes(1, 3, 4), vendors(TcfTranslator.ZEOTAP_GVL_ID), opts).track)
        assertTrue(TcfTranslator.translate(purposes(1, 3, 4), vendors(TcfTranslator.ZEOTAP_GVL_OLD_ID), opts).track)
    }

    @Test
    fun `identify always requires Zeotap vendor consent plus purposes 1 and 9`() {
        assertFalse(TcfTranslator.translate(purposes(1, 9), null, defaults).identify)
        assertTrue(TcfTranslator.translate(purposes(1, 9), vendors(TcfTranslator.ZEOTAP_GVL_ID), defaults).identify)
        assertTrue(TcfTranslator.translate(purposes(1, 9), vendors(TcfTranslator.ZEOTAP_GVL_OLD_ID), defaults).identify)
        assertFalse(TcfTranslator.translate(purposes(1), vendors(TcfTranslator.ZEOTAP_GVL_ID), defaults).identify)
        val onlyPurposeOne = options { purposesForIdentifying(listOf(1)) }
        assertTrue(TcfTranslator.translate(purposes(1), vendors(TcfTranslator.ZEOTAP_GVL_ID), onlyPurposeOne).identify)
    }

    @Test
    fun `cookieSync uses the default groups or custom purposes`() {
        assertTrue(TcfTranslator.translate(purposes(1, 5, 6), null, defaults).cookieSync)
        assertTrue(TcfTranslator.translate(purposes(1), null, options { purposesForCookieSync(listOf(1)) }).cookieSync)
        assertFalse(TcfTranslator.translate(purposes(2), null, defaults).cookieSync)
    }

    @Test
    fun `no purpose consents at all denies everything, an empty map too`() {
        assertEquals(ConsentSignals.uniform(false), TcfTranslator.translate(null, vendors(TcfTranslator.ZEOTAP_GVL_ID), defaults))
        assertEquals(ConsentSignals.uniform(false), TcfTranslator.translate(emptyMap(), vendors(TcfTranslator.ZEOTAP_GVL_ID), defaults))
    }

    @Test
    fun `bit strings decode 1-based with null for an absent key`() {
        assertNull(TcfTranslator.bitStringToConsents(null))
        assertEquals(emptyMap(), TcfTranslator.bitStringToConsents(""))
        assertEquals(mapOf(1 to true, 2 to false, 3 to true), TcfTranslator.bitStringToConsents("101"))
    }

    @Test
    fun `translate over TcData decodes both bit strings`() {
        val vendorBits = CharArray(TcfTranslator.ZEOTAP_GVL_ID) { '0' }.also { it[TcfTranslator.ZEOTAP_GVL_ID - 1] = '1' }.concatToString()
        val data = TcData(cmpSdkId = 1, gdprApplies = true, tcString = "TC", purposeConsents = "101100001", vendorConsents = vendorBits)
        assertEquals(ConsentSignals.uniform(true), TcfTranslator.translate(data, defaults))

        val truncated = data.copy(vendorConsents = vendorBits.substring(0, 400))
        assertEquals(ConsentSignals(track = true, identify = false, cookieSync = true), TcfTranslator.translate(truncated, defaults))

        assertEquals(ConsentSignals.uniform(false), TcfTranslator.translate(data.copy(purposeConsents = null), defaults))
    }

    @Test
    fun `constants match the web SDK`() {
        assertEquals(1469, TcfTranslator.ZEOTAP_GVL_ID)
        assertEquals(301, TcfTranslator.ZEOTAP_GVL_OLD_ID)
        assertEquals(listOf(listOf(1, 3, 4), listOf(1, 5, 6)), TcfTranslator.DEFAULT_TRACKING_PURPOSES)
        assertEquals(listOf(1, 9), TcfTranslator.DEFAULT_IDENTIFYING_PURPOSES)
        assertEquals(listOf(listOf(1, 3, 4), listOf(1, 5, 6)), TcfTranslator.DEFAULT_COOKIE_SYNC_PURPOSES)
    }
}
