package com.zeotap.sdk.internal.json

/**
 * Formats a finite, non-integral number the way JavaScript's
 * `Number.prototype.toString()` (hence `JSON.stringify`) does, so a property
 * such as `12345678.9` or `1e21` is byte-identical on the wire to what the
 * browser SDK sends (`Double.toString()` would give `1.23456789E7` / `1.0E21`).
 *
 * Rules (ECMA-262 Number::toString, radix 10) over the shortest round-trip
 * digits `d1…dk` and decimal exponent `n` (value = 0.d1…dk × 10^n):
 *  - k ≤ n ≤ 21  → digits followed by n−k zeros
 *  - 0 < n ≤ 21  → digits with a point after the n-th
 *  - −6 < n ≤ 0  → "0." + (−n zeros) + digits
 *  - otherwise   → d1[.d2…dk]e±(n−1)
 *
 * The digits come from `java.lang.Double.toString` / `Float.toString`, which
 * yield the shortest round-trip representation for the values seen in practice
 * (guaranteed on JDK ≥ 19 / recent ART; older JDKs may emit one extra digit in
 * rare cases). Floats keep their own shortest digits (`0.1f` → `0.1`).
 */
internal object JsNumber {
    fun format(value: Double): String {
        require(!value.isNaN() && !value.isInfinite()) { "non-finite" }
        if (value == 0.0) return "0"
        return fromJavaRepr(java.lang.Double.toString(Math.abs(value)), value < 0)
    }

    fun format(value: Float): String {
        require(!value.isNaN() && !value.isInfinite()) { "non-finite" }
        if (value == 0.0f) return "0"
        return fromJavaRepr(java.lang.Float.toString(Math.abs(value)), value < 0)
    }

    /** `repr` is Java's `"123.456"` | `"1.23456785E7"` | `"1.0E-5"` form of a positive number. */
    private fun fromJavaRepr(repr: String, negative: Boolean): String {
        val ePos = repr.indexOf('E')
        val mantissa = if (ePos >= 0) repr.substring(0, ePos) else repr
        val exp = if (ePos >= 0) repr.substring(ePos + 1).toInt() else 0
        val dot = mantissa.indexOf('.')
        val intPart = if (dot >= 0) mantissa.substring(0, dot) else mantissa
        val fracPart = if (dot >= 0) mantissa.substring(dot + 1) else ""

        val digits: String
        val n: Int
        if (intPart != "0") {
            digits = (intPart + fracPart).trimEnd('0')
            n = intPart.length + exp
        } else {
            val leadingZeros = fracPart.length - fracPart.trimStart('0').length
            digits = fracPart.trimStart('0').trimEnd('0')
            n = -leadingZeros + exp
        }
        val k = digits.length
        val body = when {
            k <= n && n <= 21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val rest = if (k > 1) "." + digits.substring(1) else ""
                digits.substring(0, 1) + rest + "e" + (if (e >= 0) "+" else "-") + Math.abs(e)
            }
        }
        return if (negative) "-$body" else body
    }
}
