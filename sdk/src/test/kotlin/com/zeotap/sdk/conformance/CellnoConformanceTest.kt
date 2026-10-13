package com.zeotap.sdk.conformance

import com.zeotap.sdk.internal.identity.Hasher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

/** `cellno` family → [Hasher.cleanCellno]. `expected.cc` absent ⇔ null. */
@RunWith(Parameterized::class)
class CellnoConformanceTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = VectorLoader.parameters("cellno")
    }

    @Test
    fun conforms() {
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        val actual = Hasher.cleanCellno(input.string("raw"), input.stringOrNull("explicitCc"))
        assertEquals(Hasher.Cellno(expected.string("number"), expected.stringOrNull("cc")), actual, name)
    }
}
