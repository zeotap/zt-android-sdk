package com.zeotap.sdk.conformance

import com.zeotap.sdk.internal.storage.Keys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

/** `storage-keys` family → [Keys]. */
@RunWith(Parameterized::class)
class StorageKeysConformanceTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = VectorLoader.parameters("storage-keys")
    }

    @Test
    fun conforms() {
        val keys = Keys(case.getValue("input").jsonObject.string("namespace"))
        val expected = case.getValue("expected").jsonObject
        assertEquals(expected.string("anonymousId"), keys.anonymousId, "$name anonymousId")
        assertEquals(expected.string("userId"), keys.userId, "$name userId")
        assertEquals(expected.string("userIdentities"), keys.userIdentities, "$name userIdentities")
        assertEquals(expected.string("consent"), keys.consent, "$name consent")
        assertEquals(expected.string("consentTcf"), keys.consentTcf, "$name consentTcf")
    }
}
