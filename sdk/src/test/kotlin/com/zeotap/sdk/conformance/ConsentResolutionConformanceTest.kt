package com.zeotap.sdk.conformance

import com.zeotap.sdk.internal.consent.ConsentManager
import com.zeotap.sdk.internal.consent.ConsentState
import com.zeotap.sdk.internal.testing.FakeClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

/**
 * `consent-resolution` family → [ConsentManager] with a settable clock (`setConsent` / `resolveDefault`
 * stamp `atMs`; `offer` carries its own `ts`).
 */
@RunWith(Parameterized::class)
class ConsentResolutionConformanceTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = VectorLoader.parameters("consent-resolution")
    }

    @Test
    fun conforms() {
        val clock = FakeClock(0)
        val manager = ConsentManager(clock)
        var listenerCalls = 0
        manager.onResolve { listenerCalls++ }
        val afterEachStep = case.getValue("expected").jsonObject.getValue("afterEachStep").jsonArray

        case.getValue("input").jsonObject.getValue("steps").jsonArray.forEachIndexed { i, element ->
            val step = element.jsonObject
            var accepted: Boolean? = null
            when (val op = step.string("op")) {
                "setConsent" -> {
                    clock.nowMs = requireNotNull(step.longOrNull("atMs"))
                    manager.setConsent(requireNotNull(step.objOrNull("input")).toConsentInput())
                }
                "resolveDefault" -> {
                    clock.nowMs = requireNotNull(step.longOrNull("atMs"))
                    manager.resolveDefault(requireNotNull(step.boolOrNull("granted")))
                }
                "offer" -> accepted = manager.offer(step.toResolvedConsent())
                "clear" -> manager.clear()
                else -> error("$name: unknown op $op")
            }
            assertStep(afterEachStep[i].jsonObject, manager, accepted, listenerCalls, "$name step $i")
        }
    }

    private fun assertStep(exp: JsonObject, manager: ConsentManager, accepted: Boolean?, listenerCalls: Int, ctx: String) {
        exp.boolOrNull("accepted")?.let { assertEquals(it, accepted, "$ctx accepted") }
        assertEquals(exp.boolOrNull("resolved"), manager.isResolved(), "$ctx resolved")
        val resolution = manager.getResolution()
        assertEquals(exp.stringOrNull("source"), resolution?.source?.wire, "$ctx source")
        assertEquals(exp.objOrNull("signals")?.toSignals(), resolution?.signals, "$ctx signals")
        assertEquals(exp.stringOrNull("tcfString"), resolution?.tcfString, "$ctx tcfString")
        assertEquals(exp.boolOrNull("gdprApplies"), resolution?.gdprApplies, "$ctx gdprApplies")
        assertEquals(exp.longOrNull("ts"), resolution?.ts, "$ctx ts")
        assertEquals(exp.objOrNull("getConsent"), manager.getConsent()?.let(ConsentState::toJson), "$ctx getConsent")
        assertEquals(requireNotNull(exp.longOrNull("listenerCalls")).toInt(), listenerCalls, "$ctx listenerCalls")
    }
}
