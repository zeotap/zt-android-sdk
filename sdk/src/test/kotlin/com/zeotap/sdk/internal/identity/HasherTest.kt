package com.zeotap.sdk.internal.identity

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mirrors `sdk/js/src/hash.test.ts` plus the JVM-specific traps (JS trim set, Locale.ROOT casing). */
class HasherTest {
    private val nbsp = 0xA0.toChar().toString()
    private val bom = 0xFEFF.toChar().toString()

    @Test
    fun `sha256 and sha1 produce lowercase hex matching the RFC vectors`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hasher.sha256Hex("abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Hasher.sha1Hex("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hasher.sha256Hex(""))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Hasher.sha1Hex(""))
    }

    @Test
    fun `digests use UTF-8 bytes without normalisation`() {
        assertEquals("ade92ed8322c4edc818c46ec81985bfad9c58fb9443f8f108ddc0edee53ea96f", Hasher.sha256Hex("jürgen@exämple.de"))
        val nfc = "é"
        val nfd = "e" + 0x0301.toChar()
        assertEquals("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c", Hasher.sha256Hex(nfc))
        assertEquals("bf12767b0f2a56b2190075bae8169f656e3ce8d6357d4aff184bc6c7ea48f9f6", Hasher.sha256Hex(nfd))
    }

    @Test
    fun `a lone surrogate encodes as U+FFFD like TextEncoder, not as a question mark`() {
        val lone = 0xD800.toChar().toString()
        assertEquals(Hasher.sha256Hex(0xFFFD.toChar().toString()), Hasher.sha256Hex(lone))
    }

    @Test
    fun `caseVariants produces the legacy 4-key matrix in order with case applied before hashing`() {
        val out = Hasher.caseVariants("  User@Example.COM ")
        assertEquals(listOf("sha256_lowercase", "sha256_uppercase", "sha1_lowercase", "sha1_uppercase"), out.keys.toList())
        assertEquals(Hasher.sha256Hex("user@example.com"), out["sha256_lowercase"])
        assertEquals(Hasher.sha256Hex("USER@EXAMPLE.COM"), out["sha256_uppercase"])
        assertEquals(Hasher.sha1Hex("user@example.com"), out["sha1_lowercase"])
        assertEquals(Hasher.sha1Hex("USER@EXAMPLE.COM"), out["sha1_uppercase"])
    }

    @Test
    fun `digits produces sha256 and sha1 of the exact string`() {
        val out = Hasher.digits("919999999999")
        assertEquals(listOf("sha256", "sha1"), out.keys.toList())
        assertEquals("5a15bf8887c41bb21f3b33a5bf1a06064711a6495cbbd97ddb92995d5df8b1b5", out["sha256"])
        assertEquals("a983675bd344456da24fde5fc32c12e15ae17157", out["sha1"])
    }

    @Test
    fun `jsTrim strips the ECMAScript whitespace set including NBSP, BOM and line separators`() {
        assertEquals("x", Hasher.jsTrim("$nbsp x $nbsp"))
        assertEquals("x", Hasher.jsTrim("${bom}x${0x2028.toChar()}${0x3000.toChar()}"))
        assertEquals("x", Hasher.jsTrim("\t\n\r x"))
        assertEquals("", Hasher.jsTrim("   "))
        assertEquals(Hasher.caseVariants("User@Example.COM"), Hasher.caseVariants("${nbsp}User@Example.COM$nbsp"))
    }

    @Test
    fun `jsTrim does not strip control characters that Java considers whitespace`() {
        val fs = 0x1C.toChar().toString()
        assertEquals("${fs}x", Hasher.jsTrim("${fs}x"))
    }

    @Test
    fun `full Unicode case mapping expands sharp s and dotted I`() {
        assertEquals("STRASSE", "straße".uppercase(Locale.ROOT))
        assertEquals("i" + 0x0307.toChar() + "stanbul", "İstanbul".lowercase(Locale.ROOT))
        assertEquals(
            "fd3cb47d5c6af18920db606881aedb3c1f28684c82e6eab0fe49bfcf304aa8ba",
            Hasher.caseVariants("straße@example.de")["sha256_uppercase"],
        )
        assertEquals(
            "21b3d4ad954ffe193659bcdcff92053ab646244b3898969a09265d00b2af7ba4",
            Hasher.caseVariants("İstanbul@example.com")["sha256_lowercase"],
        )
    }

    @Test
    fun `casing ignores the device locale (Turkish dotless i trap)`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("tr", "TR"))
        try {
            assertEquals(Hasher.sha256Hex("i"), Hasher.caseVariants("I")["sha256_lowercase"])
            assertEquals(Hasher.sha256Hex("I"), Hasher.caseVariants("i")["sha256_uppercase"])
            assertEquals(
                "21b3d4ad954ffe193659bcdcff92053ab646244b3898969a09265d00b2af7ba4",
                Hasher.caseVariants("İstanbul@example.com")["sha256_lowercase"],
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `cleanCellno strips formatting characters`() {
        assertEquals(Hasher.Cellno("9999999999", null), Hasher.cleanCellno("(999) 999-9999", null))
        assertEquals(Hasher.Cellno("919999999999", null), Hasher.cleanCellno("+91-9999999999", null))
    }

    @Test
    fun `cleanCellno splits the country code on the legacy space format`() {
        assertEquals(Hasher.Cellno("9999999999", "91"), Hasher.cleanCellno("91 9999999999", null))
        assertEquals(Hasher.Cellno("9999999999", "91"), Hasher.cleanCellno("+91 (99999) 99999", null))
        assertEquals(Hasher.Cellno("7700900123", "44"), Hasher.cleanCellno(" 44  7700 900123", null))
        assertEquals(Hasher.Cellno("5", "1234"), Hasher.cleanCellno("+1234 5", null))
        assertEquals(Hasher.Cellno("123456", null), Hasher.cleanCellno("+12345 6", null))
    }

    @Test
    fun `cleanCellno lets an explicit cc win and digit-strips it`() {
        assertEquals(Hasher.Cellno("9999999999", "1"), Hasher.cleanCellno("91 9999999999", "1"))
        assertEquals(Hasher.Cellno("9999999999", "91"), Hasher.cleanCellno("9999999999", "91"))
        assertEquals(Hasher.Cellno("9999999999", "91"), Hasher.cleanCellno("9999999999", "+91"))
        assertNull(Hasher.cleanCellno("9999999999", "").cc)
        assertNull(Hasher.cleanCellno("9999999999", "+-").cc)
    }

    @Test
    fun `cleanCellno only counts ASCII digits`() {
        assertEquals(Hasher.Cellno("123", null), Hasher.cleanCellno("٩٩٩ 123", null))
        assertEquals(Hasher.Cellno("", null), Hasher.cleanCellno("abc", null))
        assertEquals(Hasher.Cellno("", null), Hasher.cleanCellno("", null))
    }
}
