package com.zeotap.sdk.conformance

import com.zeotap.sdk.ConsentSignals
import com.zeotap.sdk.internal.consent.ConsentState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

/** `consent-signals` family → [ConsentState.resolveSignals] / [ConsentSignals.uniform]. */
@RunWith(Parameterized::class)
class ConsentSignalsConformanceTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = VectorLoader.parameters("consent-signals")
    }

    @Test
    fun conforms() {
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject.toSignals()
        val uniform = input.boolOrNull("uniform")
        val actual = if (uniform != null) ConsentSignals.uniform(uniform) else ConsentState.resolveSignals(input.toConsentInput())
        assertEquals(expected, actual, name)
        if (uniform == null) {
            // The persisted-record key order is contractual too.
            assertEquals(case.getValue("expected").jsonObject.toString(), ConsentState.signalsToJson(actual).toString(), "$name json")
        }
    }
}
