package com.zeotap.sdk.conformance

import com.zeotap.sdk.internal.consent.ConsentPersistence
import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.testing.FakeClock
import com.zeotap.sdk.internal.testing.InMemoryStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

/**
 * `consent-persistence` family → [ConsentPersistence] with a pinned clock over an in-memory storage.
 * Save cases: per save, the `set` count delta, `main`/`tcf` structurally, `mainJson`/`tcfJson` byte-exact,
 * then `loaded`. Load cases: seeded storage → `result` and the exact `storageAfter` snapshot.
 */
@RunWith(Parameterized::class)
class ConsentPersistenceConformanceTest(private val name: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = VectorLoader.parameters("consent-persistence")
    }

    private val input = case.getValue("input").jsonObject
    private val expected = case.getValue("expected").jsonObject
    private val nowMs = requireNotNull(input.longOrNull("nowMs"))
    private val keys = Keys(input.string("namespace"))
    private val storage = InMemoryStorage()
    private val clock = FakeClock(nowMs)
    private val persistence = ConsentPersistence(keys, storage, requireNotNull(input.longOrNull("expiryDays")).toInt(), clock)

    @Test
    fun conforms() {
        if (input.containsKey("saves")) saveCase() else loadCase()
    }

    private fun saveCase() {
        val afterEachSave = expected.getValue("afterEachSave").jsonArray
        input.getValue("saves").jsonArray.forEachIndexed { i, element ->
            val save = element.jsonObject
            clock.nowMs = nowMs + (save.longOrNull("atMs") ?: 0L)
            val writesBefore = storage.writes
            persistence.save(save.getValue("resolution").jsonObject.toResolvedConsent())

            val exp = afterEachSave[i].jsonObject
            val ctx = "$name save $i"
            assertEquals(requireNotNull(exp.longOrNull("writes")).toInt(), storage.writes - writesBefore, "$ctx writes")
            assertRecord(exp, "main", "mainJson", storage.get(keys.consent), ctx)
            assertRecord(exp, "tcf", "tcfJson", storage.get(keys.consentTcf), ctx)
        }
        assertEquals(expected.objOrNull("loaded"), persistence.load()?.toVectorJson(), "$name loaded")
    }

    private fun loadCase() {
        for ((key, value) in input.getValue("storage").jsonObject) storage.set(key, value.jsonPrimitive.content)
        storage.resetCounters()
        assertEquals(expected.objOrNull("result"), persistence.load()?.toVectorJson(), "$name result")
        val storageAfter = expected.getValue("storageAfter").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(storageAfter, storage.snapshot(), "$name storageAfter")
    }

    private fun assertRecord(exp: JsonObject, structuralKey: String, exactKey: String, actual: String?, ctx: String) {
        assertEquals(exp.stringOrNull(exactKey), actual, "$ctx $exactKey")
        val structural = exp.objOrNull(structuralKey)
        assertEquals(structural, actual?.let { Json.parseToJsonElement(it).jsonObject }, "$ctx $structuralKey")
    }
}
