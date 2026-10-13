package com.zeotap.sdk.internal.consent

import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentSignals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors the `resolveSignals` / `uniformSignals` half of `consent-state.test.ts` and the wire form. */
class ConsentStateTest {
    private fun parsed(
        analytics: Boolean? = null,
        marketing: Boolean? = null,
        advertising: Boolean? = null,
        functional: Boolean? = null,
    ) = ConsentInput.Parsed(analytics, marketing, advertising, functional)

    @Test
    fun `parsed track requires analytics not false (absent grants)`() {
        assertTrue(ConsentState.resolveSignals(parsed(analytics = true)).track)
        assertTrue(ConsentState.resolveSignals(parsed()).track)
        assertTrue(ConsentState.resolveSignals(parsed(advertising = true)).track)
        assertFalse(ConsentState.resolveSignals(parsed(analytics = false)).track)
    }

    @Test
    fun `parsed identify follows track`() {
        assertTrue(ConsentState.resolveSignals(parsed(analytics = true)).identify)
        assertFalse(ConsentState.resolveSignals(parsed(analytics = false)).identify)
        assertEquals(
            ConsentSignals(track = false, identify = false, cookieSync = true),
            ConsentState.resolveSignals(parsed(analytics = false, advertising = true)),
        )
    }

    @Test
    fun `parsed cookieSync requires advertising or marketing`() {
        assertTrue(ConsentState.resolveSignals(parsed(advertising = true)).cookieSync)
        assertTrue(ConsentState.resolveSignals(parsed(marketing = true)).cookieSync)
        assertFalse(ConsentState.resolveSignals(parsed(analytics = true)).cookieSync)
        assertFalse(ConsentState.resolveSignals(parsed(functional = true)).cookieSync)
        assertFalse(ConsentState.resolveSignals(parsed()).cookieSync)
    }

    @Test
    fun `manual tcfString grants everything, an empty one denies cookieSync only`() {
        assertEquals(ConsentSignals.uniform(true), ConsentState.resolveSignals(ConsentInput.Tcf("CO...")))
        assertEquals(ConsentSignals(track = true, identify = true, cookieSync = false), ConsentState.resolveSignals(ConsentInput.Tcf("")))
    }

    @Test
    fun `usPrivacy grants track and identify and parses the opt-out-of-sale character`() {
        assertEquals(ConsentSignals.uniform(true), ConsentState.resolveSignals(ConsentInput.UsPrivacy("1YNN")))
        assertFalse(ConsentState.resolveSignals(ConsentInput.UsPrivacy("1YYN")).cookieSync)
        assertFalse(ConsentState.resolveSignals(ConsentInput.UsPrivacy("1yyn")).cookieSync)
        assertTrue(ConsentState.resolveSignals(ConsentInput.UsPrivacy("1---")).cookieSync)
        assertTrue(ConsentState.resolveSignals(ConsentInput.UsPrivacy("1YN")).cookieSync)
        assertTrue(ConsentState.resolveSignals(ConsentInput.UsPrivacy("1YNNN")).cookieSync)
        assertFalse(ConsentState.resolveSignals(ConsentInput.UsPrivacy("1Y")).cookieSync)
        assertFalse(ConsentState.resolveSignals(ConsentInput.UsPrivacy("")).cookieSync)
        assertTrue(ConsentState.resolveSignals(ConsentInput.UsPrivacy("")).track)
    }

    @Test
    fun `consentPermitsSync fails safe on null`() {
        assertFalse(ConsentState.consentPermitsSync(null))
        assertTrue(ConsentState.consentPermitsSync(ConsentInput.Tcf("x")))
        assertFalse(ConsentState.consentPermitsSync(ConsentInput.Tcf("")))
    }

    @Test
    fun `uniform grants or denies all signals`() {
        assertEquals(ConsentSignals(track = true, identify = true, cookieSync = true), ConsentSignals.uniform(true))
        assertEquals(ConsentSignals(track = false, identify = false, cookieSync = false), ConsentSignals.uniform(false))
    }

    @Test
    fun `toJson serialises each form in canonical order`() {
        assertEquals("""{"tcfString":"CO1"}""", ConsentState.toJson(ConsentInput.Tcf("CO1")).toString())
        assertEquals("""{"usPrivacy":"1YNN"}""", ConsentState.toJson(ConsentInput.UsPrivacy("1YNN")).toString())
        assertEquals(
            """{"analytics":false,"marketing":true,"advertising":false,"functional":true}""",
            ConsentState.toJson(parsed(functional = true, advertising = false, marketing = true, analytics = false)).toString(),
        )
        assertEquals("""{"advertising":true}""", ConsentState.toJson(parsed(advertising = true)).toString())
        assertEquals("{}", ConsentState.toJson(parsed()).toString())
    }

    @Test
    fun `fromJson inverts toJson and detects the form by key`() {
        assertEquals(ConsentInput.Tcf("CO1"), ConsentState.fromJson(Json.parseToJsonElement("""{"tcfString":"CO1"}""").jsonObject))
        assertEquals(ConsentInput.UsPrivacy("1YNN"), ConsentState.fromJson(Json.parseToJsonElement("""{"usPrivacy":"1YNN"}""").jsonObject))
        assertEquals(
            parsed(analytics = true, functional = false),
            ConsentState.fromJson(Json.parseToJsonElement("""{"functional":false,"analytics":true}""").jsonObject),
        )
        assertEquals(parsed(), ConsentState.fromJson(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun `signals round-trip through JSON in the contractual key order`() {
        val signals = ConsentSignals(track = true, identify = false, cookieSync = true)
        val json = ConsentState.signalsToJson(signals)
        assertEquals("""{"track":true,"identify":false,"cookieSync":true}""", json.toString())
        assertEquals(signals, ConsentState.signalsFromJson(json))
        assertEquals(ConsentSignals.uniform(false), ConsentState.signalsFromJson(Json.parseToJsonElement("{}").jsonObject))
    }
}
