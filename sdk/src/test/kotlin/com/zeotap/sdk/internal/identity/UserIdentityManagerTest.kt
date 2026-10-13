package com.zeotap.sdk.internal.identity

import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.testing.InMemoryStorage
import com.zeotap.sdk.internal.testing.RecordingLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors `sdk/js/src/user-identities.test.ts` (minus the browser-only insecure-context case). */
class UserIdentityManagerTest {
    private val storage = InMemoryStorage()
    private val keys = Keys("zeotap")
    private val logger = RecordingLogger()

    private fun manager() = UserIdentityManager(keys, storage, logger)

    private fun ids(vararg pairs: Pair<String, String?>): Map<String, String?> = linkedMapOf(*pairs)

    private fun JsonObject.group(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun `hashes email into the 4-variant matrix`() {
        val mgr = manager()
        mgr.setIdentities(ids("email" to "User@Example.com"), hashed = false)
        val email = assertNotNull(mgr.get()).group("email")
        assertEquals(Hasher.sha256Hex("user@example.com"), email.str("sha256_lowercase"))
        assertEquals(listOf("sha256_lowercase", "sha256_uppercase", "sha1_lowercase", "sha1_uppercase"), email.keys.toList())
    }

    @Test
    fun `hashes cellno and derives cellno_e164 when the cc is known`() {
        val mgr = manager()
        mgr.setIdentities(ids("cellno" to "91 99999-99999"), hashed = false)
        val stored = assertNotNull(mgr.get())
        assertEquals(Hasher.sha256Hex("9999999999"), stored.group("cellno").str("sha256"))
        assertEquals(Hasher.sha256Hex("919999999999"), stored.group("cellno_e164").str("sha256"))
    }

    @Test
    fun `uses the cellno_cc field for e164 derivation and consumes it`() {
        val mgr = manager()
        mgr.setIdentities(ids("cellno" to "9999999999", "cellno_cc" to "91"), hashed = false)
        val stored = assertNotNull(mgr.get())
        assertEquals(Hasher.sha256Hex("919999999999"), stored.group("cellno_e164").str("sha256"))
        assertFalse(stored.containsKey("cellno_cc"))
    }

    @Test
    fun `no cc means no cellno_e164`() {
        val mgr = manager()
        mgr.setIdentities(ids("cellno" to "9999999999"), hashed = false)
        assertNull(assertNotNull(mgr.get())["cellno_e164"])
    }

    @Test
    fun `cellno without digits and cellno_cc alone emit nothing`() {
        val mgr = manager()
        mgr.setIdentities(ids("cellno" to "abc"), hashed = false)
        assertNull(mgr.get())
        mgr.setIdentities(ids("cellno_cc" to "91"), hashed = false)
        assertNull(mgr.get())
        assertNull(storage.get("zeotap_user_identities"))
    }

    @Test
    fun `fpuid and custom keys pass through raw`() {
        val mgr = manager()
        mgr.setIdentities(ids("fpuid" to "fp-1", "crmId" to "crm-9"), hashed = false)
        assertEquals(JsonObject(mapOf("fpuid" to JsonPrimitive("fp-1"), "crmId" to JsonPrimitive("crm-9"))), mgr.get())
    }

    @Test
    fun `merges across calls and skips empty or null values`() {
        val mgr = manager()
        mgr.setIdentities(ids("fpuid" to "fp-1"), hashed = false)
        mgr.setIdentities(ids("crmId" to "crm-9", "email" to "", "loginid" to null), hashed = false)
        assertEquals(JsonObject(mapOf("fpuid" to JsonPrimitive("fp-1"), "crmId" to JsonPrimitive("crm-9"))), mgr.get())
    }

    @Test
    fun `merge keeps existing key positions and appends new ones`() {
        val mgr = manager()
        mgr.setIdentities(ids("email" to "a@b.com", "fpuid" to "fp-1"), hashed = false)
        mgr.setIdentities(ids("crm" to "x", "email" to "c@d.com"), hashed = false)
        assertEquals(listOf("email", "fpuid", "crm"), assertNotNull(mgr.get()).keys.toList())
        assertEquals(Hasher.sha256Hex("c@d.com"), assertNotNull(mgr.get()).group("email").str("sha256_lowercase"))
    }

    @Test
    fun `regroups flat pre-hashed legacy keys`() {
        val mgr = manager()
        mgr.setIdentities(
            ids(
                "email_sha256_lowercase" to "aa",
                "email_md5_uppercase" to "bb",
                "cellno_sha256" to "cc",
                "cellno_e164_sha1" to "dd",
                "fpuid" to "fp-1",
            ),
            hashed = true,
        )
        assertEquals(
            """{"email":{"sha256_lowercase":"aa","md5_uppercase":"bb"},"cellno":{"sha256":"cc"},"cellno_e164":{"sha1":"dd"},"fpuid":"fp-1"}""",
            storage.get("zeotap_user_identities"),
        )
    }

    @Test
    fun `hashed mode leaves unmatched keys raw and replaces an existing group wholesale`() {
        val mgr = manager()
        mgr.setIdentities(ids("email" to "a@b.com"), hashed = false)
        mgr.setIdentities(ids("email_md5_lowercase" to "emd5l", "custom" to "raw"), hashed = true)
        assertEquals("""{"email":{"md5_lowercase":"emd5l"},"custom":"raw"}""", storage.get("zeotap_user_identities"))
    }

    @Test
    fun `persists compact JSON and reloads in a new instance`() {
        val mgr = manager()
        mgr.setIdentities(ids("fpuid" to "fp-1"), hashed = false)
        assertEquals("""{"fpuid":"fp-1"}""", storage.get("zeotap_user_identities"))
        assertEquals(mgr.get(), manager().get())
    }

    @Test
    fun `unset removes specific keys and unset with nothing clears all`() {
        val mgr = manager()
        mgr.setIdentities(ids("fpuid" to "fp-1", "crmId" to "crm-9"), hashed = false)

        mgr.unset(listOf("fpuid", "missing"))
        assertEquals(JsonObject(mapOf("crmId" to JsonPrimitive("crm-9"))), mgr.get())

        mgr.unset(null)
        assertNull(mgr.get())
        assertFalse(storage.data.containsKey("zeotap_user_identities"))
    }

    @Test
    fun `unset with an empty list and clear both remove everything`() {
        val mgr = manager()
        mgr.setIdentities(ids("fpuid" to "fp-1"), hashed = false)
        mgr.unset(emptyList())
        assertNull(mgr.get())

        mgr.setIdentities(ids("fpuid" to "fp-1"), hashed = false)
        mgr.clear()
        assertNull(mgr.get())
        assertNull(storage.get("zeotap_user_identities"))
    }

    @Test
    fun `a corrupt persisted record starts clean`() {
        storage.set("zeotap_user_identities", "{not json")
        assertNull(manager().get())
        assertTrue(logger.warns.single().startsWith("[SDK]"))
        storage.set("zeotap_user_identities", "[1,2]")
        assertNull(manager().get())
    }
}
