package com.zeotap.sdk.conformance

import com.zeotap.sdk.internal.identity.UserIdentityManager
import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.testing.InMemoryStorage
import com.zeotap.sdk.internal.testing.RecordingLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

/**
 * `identities` family → [UserIdentityManager] over an in-memory [com.zeotap.sdk.Storage]. `stored` is
 * compared structurally, `persistedJson` byte-exact, `reloaded` through a fresh manager over the same storage.
 */
@RunWith(Parameterized::class)
class IdentitiesConformanceTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = VectorLoader.parameters("identities")
    }

    @Test
    fun conforms() {
        val input = case.getValue("input").jsonObject
        val keys = Keys(input.string("namespace"))
        val storage = InMemoryStorage()
        val manager = UserIdentityManager(keys, storage, RecordingLogger())
        val expected = case.getValue("expected").jsonObject
        val afterEachStep = expected.getValue("afterEachStep").jsonArray

        input.getValue("steps").jsonArray.forEachIndexed { i, element ->
            val step = element.jsonObject
            when (val op = step.string("op")) {
                "set" -> manager.setIdentities(step.getValue("input").jsonObject.toStringMap(), step.boolOrNull("hashed") ?: false)
                "unset" -> manager.unset(step.arrayOrNull("keys")?.map { it.jsonPrimitive.content })
                "clear" -> manager.clear()
                else -> error("$name: unknown op $op")
            }
            val exp = afterEachStep[i].jsonObject
            assertEquals(exp.objOrNull("stored"), manager.get(), "$name step $i stored")
            assertEquals(exp.stringOrNull("persistedJson"), storage.get(keys.userIdentities), "$name step $i persistedJson")
        }

        val reloaded = UserIdentityManager(keys, storage, RecordingLogger())
        assertEquals(expected.objOrNull("reloaded"), reloaded.get(), "$name reloaded")
    }
}
