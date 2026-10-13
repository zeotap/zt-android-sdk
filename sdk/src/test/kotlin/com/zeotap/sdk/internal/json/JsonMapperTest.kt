package com.zeotap.sdk.internal.json

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Date

class JsonMapperTest {
    private fun encode(value: Any?): String = JsonMapper.encode(JsonMapper.fromValue(value))

    @Test
    fun `whole doubles serialise as integers like JS`() {
        assertThat(encode(1.0)).isEqualTo("1")
        assertThat(encode(0.0)).isEqualTo("0")
        assertThat(encode(-0.0)).isEqualTo("0")
        assertThat(encode(99.0)).isEqualTo("99")
        assertThat(encode(-42.0)).isEqualTo("-42")
        assertThat(encode(1.0f)).isEqualTo("1")
    }

    @Test
    fun `fractional doubles keep their fraction`() {
        assertThat(encode(2.5)).isEqualTo("2.5")
        assertThat(encode(99.5)).isEqualTo("99.5")
        assertThat(encode(0.1)).isEqualTo("0.1")
        assertThat(encode(0.1f)).isEqualTo("0.1")
        assertThat(encode(-3.25)).isEqualTo("-3.25")
    }

    @Test
    fun `doubles at or beyond 2^53 use JavaScript number formatting`() {
        // JS prints whole numbers plainly up to 1e21 and switches to exponent form (1e+21) beyond.
        assertThat(encode(1e21)).isEqualTo("1e+21")
        assertThat(encode(1e20)).isEqualTo("100000000000000000000")
        assertThat(encode(9007199254740992.0)).isEqualTo("9007199254740992")
        assertThat(encode(9007199254740991.0)).isEqualTo("9007199254740991")
    }

    @Test
    fun `NaN and infinities become null`() {
        assertThat(JsonMapper.fromValue(Double.NaN)).isEqualTo(JsonNull)
        assertThat(JsonMapper.fromValue(Double.POSITIVE_INFINITY)).isEqualTo(JsonNull)
        assertThat(JsonMapper.fromValue(Double.NEGATIVE_INFINITY)).isEqualTo(JsonNull)
        assertThat(JsonMapper.fromValue(Float.NaN)).isEqualTo(JsonNull)
        assertThat(JsonMapper.fromValue(Float.POSITIVE_INFINITY)).isEqualTo(JsonNull)
    }

    @Test
    fun `integer types serialise as integer literals`() {
        assertThat(encode(7)).isEqualTo("7")
        assertThat(encode(7L)).isEqualTo("7")
        assertThat(encode(7.toShort())).isEqualTo("7")
        assertThat(encode(7.toByte())).isEqualTo("7")
        assertThat(encode(Long.MAX_VALUE)).isEqualTo("9223372036854775807")
    }

    @Test
    fun `booleans strings chars and null`() {
        assertThat(encode(true)).isEqualTo("true")
        assertThat(encode(false)).isEqualTo("false")
        assertThat(encode("hi \"there\"")).isEqualTo("\"hi \\\"there\\\"\"")
        assertThat(encode('x')).isEqualTo("\"x\"")
        assertThat(encode(null)).isEqualTo("null")
    }

    @Test
    fun `nested maps and lists keep insertion order and null values`() {
        val props = linkedMapOf<String, Any?>(
            "order_id" to "o-1",
            "total" to 99.5,
            "quantity" to 2,
            "gift" to false,
            "coupon" to null,
            "items" to listOf(linkedMapOf("sku" to "A", "price" to 10.0)),
            "meta" to linkedMapOf("source" to "app"),
        )
        val json = JsonMapper.fromMap(props)
        assertThat(json.keys.toList()).containsExactly("order_id", "total", "quantity", "gift", "coupon", "items", "meta").inOrder()
        assertThat(json["coupon"]).isEqualTo(JsonNull)
        assertThat(JsonMapper.encode(json)).isEqualTo(
            "{\"order_id\":\"o-1\",\"total\":99.5,\"quantity\":2,\"gift\":false,\"coupon\":null," +
                "\"items\":[{\"sku\":\"A\",\"price\":10}],\"meta\":{\"source\":\"app\"}}",
        )
    }

    @Test
    fun `payload vector whole-number-double reproduces a b c`() {
        val json = JsonMapper.fromMap(linkedMapOf("a" to 1.0, "b" to 2.5, "c" to 0.0))
        assertThat(JsonMapper.encode(json)).isEqualTo("{\"a\":1,\"b\":2.5,\"c\":0}")
    }

    @Test
    fun `arrays sets and primitive arrays become JSON arrays`() {
        assertThat(encode(arrayOf("a", 1, null))).isEqualTo("[\"a\",1,null]")
        assertThat(encode(intArrayOf(1, 2))).isEqualTo("[1,2]")
        assertThat(encode(doubleArrayOf(1.0, 1.5))).isEqualTo("[1,1.5]")
        assertThat(encode(booleanArrayOf(true))).isEqualTo("[true]")
        assertThat(encode(linkedSetOf("x", "y"))).isEqualTo("[\"x\",\"y\"]")
        assertThat(JsonMapper.fromValue(emptyList<Any>())).isEqualTo(JsonArray(emptyList()))
    }

    @Test
    fun `map keys are stringified`() {
        val json = JsonMapper.fromValue(linkedMapOf(1 to "one", 2 to "two")) as JsonObject
        assertThat(json.keys.toList()).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `dates become ISO-8601 UTC strings`() {
        assertThat(encode(Date(1768472430123L))).isEqualTo("\"2026-01-15T10:20:30.123Z\"")
    }

    @Test
    fun `JsonElement values pass through untouched`() {
        val element = buildJsonObject { put("k", "v") }
        assertThat(JsonMapper.fromValue(element)).isSameInstanceAs(element)
        assertThat(JsonMapper.fromValue(JsonPrimitive("s"))).isEqualTo(JsonPrimitive("s"))
    }

    @Test
    fun `unknown types fall back to toString`() {
        class Thing {
            override fun toString() = "thing!"
        }
        assertThat(encode(Thing())).isEqualTo("\"thing!\"")
        assertThat(encode(java.math.BigDecimal("2.50"))).isEqualTo("2.5")
    }

    @Test
    fun `fromMap of empty map is empty object`() {
        assertThat(JsonMapper.encode(JsonMapper.fromMap(emptyMap()))).isEqualTo("{}")
    }
}
