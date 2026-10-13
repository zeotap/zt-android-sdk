package com.zeotap.sdk.internal.json

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins JavaScript `Number.prototype.toString()` formatting for the doubles the mapper does not turn into integers. */
class JsNumberTest {
    private fun js(value: Double) = JsNumber.format(value)

    @Test
    fun `plain decimals`() {
        assertThat(js(2.5)).isEqualTo("2.5")
        assertThat(js(0.1)).isEqualTo("0.1")
        assertThat(js(99.99)).isEqualTo("99.99")
        assertThat(js(-3.75)).isEqualTo("-3.75")
        assertThat(js(12345678.9)).isEqualTo("12345678.9") // Double.toString gives 1.23456789E7
        assertThat(js(0.000001)).isEqualTo("0.000001")
        assertThat(js(0.0000001)).isEqualTo("1e-7")
        assertThat(js(1.5e-7)).isEqualTo("1.5e-7")
    }

    @Test
    fun `large magnitudes switch to exponent form at 1e21 like JavaScript`() {
        assertThat(js(1e21)).isEqualTo("1e+21")
        assertThat(js(1.5e21)).isEqualTo("1.5e+21")
        assertThat(js(1e20)).isEqualTo("100000000000000000000")
        assertThat(js(123456789012345680000.0)).isEqualTo("123456789012345680000")
        assertThat(js(9007199254740993.0)).isEqualTo("9007199254740992") // 2^53+1 rounds to an even double
        assertThat(js(-1e21)).isEqualTo("-1e+21")
    }

    @Test
    fun `mapper routes non-integral and huge doubles through the JS formatter`() {
        assertThat(JsonMapper.encode(JsonMapper.fromMap(mapOf("a" to 1.0, "b" to 2.5, "c" to -0.0, "d" to 1e21, "e" to 12345678.9))))
            .isEqualTo("""{"a":1,"b":2.5,"c":0,"d":1e+21,"e":12345678.9}""")
    }
}
