package com.zeotap.sdk.conformance

import com.zeotap.sdk.internal.identity.Hasher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

/** `hash` family → [Hasher]. */
@RunWith(Parameterized::class)
class HashConformanceTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = VectorLoader.parameters("hash")
    }

    @Test
    fun conforms() {
        val input = case.getValue("input").jsonObject
        val value = input.string("value")
        when (val op = input.string("op")) {
            "digestHex" -> {
                val actual = when (val algorithm = input.string("algorithm")) {
                    "SHA-256" -> Hasher.sha256Hex(value)
                    "SHA-1" -> Hasher.sha1Hex(value)
                    else -> error("$name: unknown algorithm $algorithm")
                }
                assertEquals(case.getValue("expected").jsonPrimitive.content, actual, name)
            }
            "hashCaseVariants" -> assertOrderedMap(case.getValue("expected").jsonObject, Hasher.caseVariants(value))
            "hashDigits" -> assertOrderedMap(case.getValue("expected").jsonObject, Hasher.digits(value))
            else -> error("$name: unknown op $op")
        }
    }

    private fun assertOrderedMap(expected: JsonObject, actual: LinkedHashMap<String, String>) {
        assertEquals(expected.keys.toList(), actual.keys.toList(), "$name key order")
        val expectedValues: Map<String, String> = expected.mapValues { it.value.jsonPrimitive.content }
        assertEquals(expectedValues, actual, name)
    }
}
