package com.zeotap.sdk.conformance

import com.zeotap.sdk.internal.consent.tcf.TcData
import com.zeotap.sdk.internal.consent.tcf.TcfTranslator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

/**
 * `tcf` family → [TcfTranslator]. The IAB TCF-for-Apps bit strings (`input.iabtcf.*`) are the mobile
 * input and must reach `expectedFromBits` when present (bit-string truncation), else `expected`; the
 * TCData map form must always reach `expected`.
 */
@RunWith(Parameterized::class)
class TcfConformanceTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = VectorLoader.parameters("tcf")
    }

    private val input get() = case.getValue("input").jsonObject
    private val options get() = input.getValue("options").jsonObject.toConsentOptions()

    @Test
    fun `bit strings conform`() {
        val iab = input.getValue("iabtcf").jsonObject
        val purposeBits = iab.stringOrNull("IABTCF_PurposeConsents")
        val vendorBits = iab.stringOrNull("IABTCF_VendorConsents")
        val expected = (case.objOrNull("expectedFromBits") ?: case.getValue("expected").jsonObject).toSignals()

        val fromMaps = TcfTranslator.translate(
            TcfTranslator.bitStringToConsents(purposeBits),
            TcfTranslator.bitStringToConsents(vendorBits),
            options,
        )
        assertEquals(expected, fromMaps, "$name via bitStringToConsents")

        val tcData = TcData(cmpSdkId = 1, gdprApplies = true, tcString = "TC", purposeConsents = purposeBits, vendorConsents = vendorBits)
        assertEquals(expected, TcfTranslator.translate(tcData, options), "$name via TcData")
    }

    @Test
    fun `TCData maps conform`() {
        val tcData = input.getValue("tcData").jsonObject
        val purposes = tcData.objOrNull("purpose")?.objOrNull("consents")?.toIntBoolMap()
        val vendors = tcData.objOrNull("vendor")?.objOrNull("consents")?.toIntBoolMap()
        val expected = case.getValue("expected").jsonObject.toSignals()
        assertEquals(expected, TcfTranslator.translate(purposes, vendors, options), name)
    }
}
