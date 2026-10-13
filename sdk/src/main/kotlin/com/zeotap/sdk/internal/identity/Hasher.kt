package com.zeotap.sdk.internal.identity

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

/**
 * PII hashing — port of `sdk/js/src/hash.ts`: SHA-256 / SHA-1 lowercase hex over UTF-8, ECMAScript
 * `trim()` then full-Unicode `Locale.ROOT` casing before hashing, and the legacy `"<cc> <number>"`
 * cellno split. MD5 is never produced on-device (web parity).
 */
internal object Hasher {
    private const val HEX = "0123456789abcdef"

    /** JS `\d` without the `u` flag is ASCII only — Arabic-Indic digits are formatting, not digits. */
    private val NON_DIGITS = Regex("[^0-9]")
    private val CC_PREFIX = Regex("^\\+?[0-9]{1,4}$")

    /** `TextEncoder` emits U+FFFD for a lone surrogate; Java's `getBytes` would emit `?`. */
    private val UTF8_REPLACEMENT = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())

    fun sha256Hex(input: String): String = digestHex("SHA-256", input)

    fun sha1Hex(input: String): String = digestHex("SHA-1", input)

    /**
     * The legacy 4-key matrix for case-sensitive identities (email, loginid): trim, then lower/upper
     * with the full Unicode mapping (`ß` → `SS`, `İ` → `i̇`), then hash. Key order is contractual.
     */
    fun caseVariants(value: String): LinkedHashMap<String, String> {
        val trimmed = jsTrim(value)
        val lower = trimmed.lowercase(Locale.ROOT)
        val upper = trimmed.uppercase(Locale.ROOT)
        return linkedMapOf(
            "sha256_lowercase" to sha256Hex(lower),
            "sha256_uppercase" to sha256Hex(upper),
            "sha1_lowercase" to sha1Hex(lower),
            "sha1_uppercase" to sha1Hex(upper),
        )
    }

    /** Digits-only identities (cellno family): sha256 + sha1 of the exact string. */
    fun digits(value: String): LinkedHashMap<String, String> =
        linkedMapOf("sha256" to sha256Hex(value), "sha1" to sha1Hex(value))

    /** A normalised cell number; `cc` is null when no country code was recognised. */
    data class Cellno(val number: String, val cc: String?)

    /**
     * Literal port of `cleanCellno` (hash.ts): trim; an explicit cc is digit-stripped; a `"<cc> <number>"`
     * split happens only when the token before the first space matches `^\+?[0-9]{1,4}$`; the number
     * keeps ASCII digits only. An explicit cc that strips to nothing suppresses the inline prefix too
     * (JS `??` keeps the empty string), and an empty cc is reported as null.
     */
    fun cleanCellno(raw: String, explicitCc: String?): Cellno {
        val trimmed = jsTrim(raw)
        var cc: String? = if (explicitCc.isNullOrEmpty()) null else NON_DIGITS.replace(explicitCc, "")
        var numberPart = trimmed
        val spaceIdx = trimmed.indexOf(' ')
        if (spaceIdx > 0) {
            val prefix = trimmed.substring(0, spaceIdx)
            if (CC_PREFIX.matches(prefix)) {
                cc = cc ?: NON_DIGITS.replace(prefix, "")
                numberPart = trimmed.substring(spaceIdx + 1)
            }
        }
        val number = NON_DIGITS.replace(numberPart, "")
        return Cellno(number, cc?.takeIf { it.isNotEmpty() })
    }

    /**
     * ECMAScript `String.prototype.trim()`: strips WhiteSpace (TAB, VT, FF, ZWNBSP and every Zs
     * character, NBSP included) and LineTerminator (LF, CR, LS, PS) from both ends. Deliberately NOT
     * `Character.isWhitespace`, which also strips U+001C–U+001F and skips NBSP.
     */
    fun jsTrim(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isJsWhitespace(value[start])) start++
        while (end > start && isJsWhitespace(value[end - 1])) end--
        return value.substring(start, end)
    }

    private fun isJsWhitespace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', '\uFEFF' -> true
        else -> Character.isSpaceChar(c)
    }

    private fun digestHex(algorithm: String, input: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(utf8(input))
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    private fun utf8(input: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(UTF8_REPLACEMENT)
        val buffer = encoder.encode(CharBuffer.wrap(input))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}
