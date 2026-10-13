package com.zeotap.sdk.conformance

import com.google.common.truth.Truth.assertThat
import com.zeotap.sdk.Callback
import com.zeotap.sdk.CmpMode
import com.zeotap.sdk.Configuration
import com.zeotap.sdk.ConsentDefault
import com.zeotap.sdk.ConsentOptions
import com.zeotap.sdk.internal.consent.ConsentState
import com.zeotap.sdk.internal.core.ClientHarness
import com.zeotap.sdk.internal.testing.InMemoryStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * sdk/conformance/vectors/gating.vectors.json — the browser SDK's consent-gate
 * scenarios replayed as scripts against the real engine: what reached the
 * transport, per-callback outcomes, the consent snapshot and the persisted
 * keys must all match.
 */
@RunWith(Parameterized::class)
class GatingConformanceTest(private val id: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "gating/{0}")
        fun cases(): Collection<Array<Any>> = VectorLoader.parameters("gating")
    }

    private fun config(options: JsonObject): Configuration {
        val consent = options["consent"]?.jsonObject
        return Configuration.Builder("https://ingest.example").apply {
            if (consent != null) {
                val b = ConsentOptions.Builder()
                consent["require"]?.jsonPrimitive?.booleanOrNull?.let { b.require(it) }
                consent["default"]?.jsonPrimitive?.contentOrNull?.let { b.default(if (it == "denied") ConsentDefault.DENIED else ConsentDefault.GRANTED) }
                consent["cmpTimeoutMs"]?.jsonPrimitive?.long?.let { b.cmpTimeoutMs(it) }
                consent["maxBufferedEvents"]?.jsonPrimitive?.int?.let { b.maxBufferedEvents(it) }
                consent["cmp"]?.jsonPrimitive?.contentOrNull?.let { b.cmp(if (it == "auto") CmpMode.AUTO else CmpMode.NONE) }
                consent["persist"]?.jsonPrimitive?.booleanOrNull?.let { b.persist(it) }
                consent["expiryDays"]?.jsonPrimitive?.int?.let { b.expiryDays(it) }
                consent(b.build())
            }
        }.build()
    }

    @Test
    fun matchesWebSdk() {
        val input = case["input"]!!.jsonObject
        val expected = case["expected"]!!.jsonObject
        val namespace = input["namespace"]!!.jsonPrimitive.content
        val storage: InMemoryStorage = ClientHarness.seededStorage(input["anonymousId"]!!.jsonPrimitive.content, namespace)
        val baseOptions = input["options"]!!.jsonObject
        val callbacks = LinkedHashMap<String, String?>()
        fun cb(step: JsonObject): Callback? = step["cb"]?.jsonPrimitive?.contentOrNull?.let { key ->
            Callback { error -> callbacks[key] = error?.message }
        }

        var h = ClientHarness(config(baseOptions), storage = storage)
        val http = h.http
        for (step in input["steps"]!!.jsonArray.map { it.jsonObject }) {
            when (step["op"]!!.jsonPrimitive.content) {
                "track" -> h.client.track(step["event"]!!.jsonPrimitive.content, null, cb(step))
                "identify" -> h.client.identify(step["userId"]?.jsonPrimitive?.contentOrNull, null, cb(step))
                "screen" -> h.client.screen(null, step["name"]!!.jsonPrimitive.content, null, cb(step))
                "setUserIdentities" -> h.client.setUserIdentities(
                    step["identities"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                    step["hashed"]?.jsonPrimitive?.booleanOrNull ?: false,
                    cb(step),
                )
                "unsetUserIdentities" -> h.client.unsetUserIdentities(step["keys"]?.jsonArray?.map { it.jsonPrimitive.content })
                "setConsent" -> h.client.setConsent(ConsentState.fromJson(step["input"]!!.jsonObject) ?: error("bad consent in $id"))
                "advanceMs" -> h.advance(step["ms"]!!.jsonPrimitive.long)
                "reset" -> h.client.reset()
                "flush" -> h.client.flush()
                "reload" -> {
                    h.flushAndSettle()
                    h.client.shutdown()
                    val opts = step["options"]?.jsonObject ?: baseOptions
                    // Same storage, same clock/scheduler/http so the whole script is one timeline.
                    h = ClientHarness(config(opts), storage = storage, clock = h.clock, scheduler = h.scheduler, ids = h.ids, http = http)
                }
                else -> error("unsupported op in $id")
            }
            h.settle()
        }
        h.flushAndSettle()

        // enqueued
        val enqueued = http.sentEvents().map { p ->
            val ctx = p["context"]!!.jsonObject
            JsonObject(
                LinkedHashMap<String, JsonElement>().apply {
                    put("type", p["type"]!!)
                    p["event"]?.let { put("event", it) }
                    p["name"]?.let { put("name", it) }
                    p["userId"]?.let { put("userId", it) }
                    ctx["consent"]?.let { put("consent", it) }
                    ctx["identities"]?.let { put("identities", it) }
                },
            )
        }
        assertThat(enqueued).isEqualTo(expected["enqueued"]!!.jsonArray.map { it.jsonObject })

        // callbacks
        val expectedCallbacks = expected["callbacks"]!!.jsonObject.mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.content }
        assertThat(callbacks.toMap()).isEqualTo(expectedCallbacks)

        // consent state
        val state = h.client.getConsentState()
        val stateJson = JsonObject(
            LinkedHashMap<String, JsonElement>().apply {
                put("resolved", JsonPrimitive(state.resolved))
                state.signals?.let {
                    put("signals", JsonObject(linkedMapOf("track" to JsonPrimitive(it.track), "identify" to JsonPrimitive(it.identify), "cookieSync" to JsonPrimitive(it.cookieSync))))
                }
                state.source?.let { put("source", JsonPrimitive(it.name.lowercase())) }
                state.gdprApplies?.let { put("gdprApplies", JsonPrimitive(it)) }
            },
        )
        assertThat(stateJson).isEqualTo(expected["consentState"]!!.jsonObject)

        // storage
        val expectedStorage = expected["storage"]!!.jsonObject
        val userId = storage.get("${namespace}_user_id")
        assertThat(userId?.let { JsonPrimitive(it) } ?: JsonNull).isEqualTo(expectedStorage["userId"])
        assertThat(storage.get("${namespace}_user_identities")?.let { Json.parseToJsonElement(it) } ?: JsonNull).isEqualTo(expectedStorage["userIdentities"])
        assertThat(storage.get("${namespace}_consent")?.let { Json.parseToJsonElement(it) } ?: JsonNull).isEqualTo(expectedStorage["consent"])
    }

    @Suppress("unused")
    private fun JsonElement.asBoolean(): Boolean = jsonPrimitive.boolean
}
