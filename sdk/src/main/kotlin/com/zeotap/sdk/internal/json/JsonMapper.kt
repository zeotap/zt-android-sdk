package com.zeotap.sdk.internal.json

import com.zeotap.sdk.internal.util.Iso8601
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.util.Date

/**
 * Host `Map<String, Any?>` → insertion-ordered kotlinx JSON tree, normalised
 * to the browser SDK's `JSON.stringify` value space so a payload built on
 * Android is byte-compatible with one built on the web.
 *
 * Number rules (JS has one number type):
 *  - whole `Double`/`Float` with `|x| < 2^53` → integer literal (`1.0` → `1`, `-0.0` → `0`)
 *  - other finite doubles → JavaScript `Number.toString()` formatting via [JsNumber]
 *    (`2.5` → `2.5`, `12345678.9` → `12345678.9`, `1e21` → `1e+21`, `1e-7` → `1e-7`)
 *  - `NaN` / `±Infinity` → `null` (what `JSON.stringify` emits)
 *  - `Long`/`Int`/`Short`/`Byte` → integer literal
 *
 * Other values: `Boolean`, `String`, `Char` (→ string), `Map` (keys `toString()`,
 * iteration order kept), `Iterable`/`Array`/primitive arrays (→ array),
 * `JsonElement` passthrough, `java.util.Date` (→ ISO-8601 UTC), `null` →
 * `JsonNull`; anything else → `toString()`.
 */
internal object JsonMapper {
    /** 2^53 — the largest magnitude a JS number represents exactly as an integer. */
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_992.0

    /** Convert a host map to a [JsonObject], keeping insertion order. */
    fun fromMap(map: Map<String, Any?>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(map.size)
        for ((key, value) in map) out[key] = fromValue(value)
        return JsonObject(out)
    }

    /** Convert any supported host value to a [JsonElement] (see class KDoc). */
    fun fromValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> fromNumber(value)
        is Char -> JsonPrimitive(value.toString())
        is Date -> JsonPrimitive(Iso8601.format(value.time))
        is Map<*, *> -> fromAnyMap(value)
        else -> fromCollectionOrString(value)
    }

    /** Compact JSON text — kotlinx's `toString()` is already `JSON.stringify`-shaped (no spaces, minimal escaping). */
    fun encode(element: JsonElement): String = element.toString()

    private fun fromNumber(value: Number): JsonElement = when (value) {
        is Double -> fromDouble(value)
        is Float -> fromFloat(value)
        is Long -> JsonPrimitive(value)
        is Int, is Short, is Byte -> JsonPrimitive(value.toLong())
        else -> fromDouble(value.toDouble()) // BigDecimal, BigInteger, atomics: JS would see a double too
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun fromDouble(value: Double): JsonElement = when {
        value.isNaN() || value.isInfinite() -> JsonNull
        isWhole(value) -> JsonPrimitive(value.toLong())
        else -> JsonUnquotedLiteral(JsNumber.format(value))
    }

    /** Floats keep their own shortest repr (`0.1f` → `0.1`, not `0.10000000149011612`). */
    @OptIn(ExperimentalSerializationApi::class)
    private fun fromFloat(value: Float): JsonElement = when {
        value.isNaN() || value.isInfinite() -> JsonNull
        isWhole(value.toDouble()) -> JsonPrimitive(value.toLong())
        else -> JsonUnquotedLiteral(JsNumber.format(value))
    }

    private fun isWhole(value: Double): Boolean =
        value == Math.floor(value) && Math.abs(value) < MAX_SAFE_INTEGER

    private fun fromAnyMap(map: Map<*, *>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(map.size)
        for ((key, value) in map) out[key.toString()] = fromValue(value)
        return JsonObject(out)
    }

    private fun fromCollectionOrString(value: Any): JsonElement = when (value) {
        is Iterable<*> -> fromIterable(value)
        is Array<*> -> fromIterable(value.asIterable())
        is IntArray -> fromIterable(value.asIterable())
        is LongArray -> fromIterable(value.asIterable())
        is ShortArray -> fromIterable(value.asIterable())
        is ByteArray -> fromIterable(value.asIterable())
        is DoubleArray -> fromIterable(value.asIterable())
        is FloatArray -> fromIterable(value.asIterable())
        is BooleanArray -> fromIterable(value.asIterable())
        is CharArray -> fromIterable(value.asIterable())
        else -> JsonPrimitive(value.toString())
    }

    private fun fromIterable(items: Iterable<*>): JsonArray {
        val out = ArrayList<JsonElement>()
        for (item in items) out.add(fromValue(item))
        return JsonArray(out)
    }
}
