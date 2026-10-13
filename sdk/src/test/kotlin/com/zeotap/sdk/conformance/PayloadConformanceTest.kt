package com.zeotap.sdk.conformance

import com.google.common.truth.Truth.assertThat
import com.zeotap.sdk.Configuration
import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.internal.consent.ConsentState
import com.zeotap.sdk.internal.core.ClientHarness
import com.zeotap.sdk.internal.core.PLATFORM_CONTEXT_KEYS
import com.zeotap.sdk.internal.core.WEB_ONLY_CONTEXT_KEYS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * sdk/conformance/vectors/payload.vectors.json — exact payloads from the real
 * engine with pinned uuid/clock/identity, compared after substituting
 * `$LIBRARY_NAME`/`$LIBRARY_VERSION` and stripping the Android-only context
 * blocks (which the web never sends) and the browser-only `ignoreContextKeys`.
 */
@RunWith(Parameterized::class)
class PayloadConformanceTest(private val id: String, private val case: JsonObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "payload/{0}")
        fun cases(): Collection<Array<Any>> = VectorLoader.parameters("payload")
    }

    @Test
    fun matchesWebSdk() {
        val input = case["input"]!!.jsonObject
        val expected = case["expected"]!!.jsonObject
        val fixture = input["fixture"]!!.jsonObject
        val namespace = input["namespace"]!!.jsonPrimitive.content
        val storage = ClientHarness.seededStorage(
            anonymousId = fixture["anonymousId"]!!.jsonPrimitive.content,
            namespace = namespace,
            extra = fixture["storage"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        val options = input["options"]!!.jsonObject
        val config = Configuration.Builder("https://ingest.example").apply {
            namespace(namespace)
            options["integration"]?.jsonObject?.let { meta ->
                integration(meta["name"]?.jsonPrimitive?.contentOrNull ?: "", meta["version"]?.jsonPrimitive?.contentOrNull ?: "")
            }
        }.build()
        val h = ClientHarness(config, storage = storage, libraryName = "\$LIBRARY_NAME", libraryVersion = "\$LIBRARY_VERSION")

        for (call in input["calls"]!!.jsonArray.map { it.jsonObject }) {
            val args = call["args"]!!.jsonArray
            fun str(i: Int): String? = args.getOrNull(i)?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            fun map(i: Int): Map<String, Any?>? = args.getOrNull(i)?.let { if (it is JsonNull) null else toKotlin(it) as Map<String, Any?> }
            when (call["method"]!!.jsonPrimitive.content) {
                "track" -> h.client.track(str(0) ?: "", map(1), null)
                "identify" -> h.client.identify(str(0), map(1), null)
                "screen" -> h.client.screen(str(0), str(1), map(2), null)
                "group" -> h.client.group(str(0) ?: "", map(1), null)
                "alias" -> h.client.alias(str(0) ?: "", str(1), null)
                "setConsent" -> h.client.setConsent(ConsentState.fromJson(args[0].jsonObject) ?: error("bad consent"))
                else -> error("unsupported method in $id")
            }
            h.settle()
        }
        h.flushAndSettle()

        // Stripped on BOTH sides, for symmetric reasons: this SDK sends context the
        // web SDK never does, and the web SDK sends context this one does not. What
        // is left is the cross-platform contract, which is what the suite is for.
        val actual = h.sentEvents().map { stripNonContract(it) }
        val expectedPayloads = expected["payloads"]!!.jsonArray.map { stripNonContract(it.jsonObject) }

        // messageIds are asserted as a SHAPE, not against the web SDK's literal
        // values, because the web SDK's uuid stream is shared with a feature this
        // SDK does not have. Its session is minted lazily at the first event, so on
        // web the stream runs messageId(1), sessionId(2), messageId(3) while here it
        // runs 1, 2 — the vectors' `fixture.uuidSequence` describes the web's
        // interleaving, and demanding it of a platform with no sessions would be
        // asserting the absence of a feature rather than the payload contract.
        //
        // What IS contractual — a fresh uuid v4 per event — is asserted directly,
        // and every other field still compares literally.
        val ids = actual.map { it["messageId"]!!.jsonPrimitive.content }
        assertThat(ids).containsNoDuplicates()
        for (id in ids) assertThat(id).matches(UUID_V4)
        assertThat(actual.mapIndexed { i, p -> withOrdinalMessageId(p, i) })
            .isEqualTo(expectedPayloads.mapIndexed { i, p -> withOrdinalMessageId(p, i) })
        assertThat(actual.map { it.keys.toList() })
            .isEqualTo(expected["topLevelKeyOrder"]!!.jsonArray.map { keys -> keys.jsonArray.map { it.jsonPrimitive.content } })
    }

    /** RFC-4122 v4, lowercase — what `IdGenerator` must produce for every event. */
    private val UUID_V4 =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").toPattern()

    /** Positional messageId, so two platforms' uuid streams can be compared at all. */
    private fun withOrdinalMessageId(payload: JsonObject, index: Int): JsonObject =
        JsonObject(LinkedHashMap(payload).apply { put("messageId", JsonPrimitive("#$index")) })

    private fun stripNonContract(payload: JsonObject): JsonObject {
        val drop = PLATFORM_CONTEXT_KEYS + WEB_ONLY_CONTEXT_KEYS
        val ctx = payload["context"]!!.jsonObject.filterKeys { it !in drop }
        return JsonObject(LinkedHashMap(payload).apply { put("context", JsonObject(ctx)) })
    }

    /** JSON → the host-map shape the public API takes (numbers become Double/Long like a JS caller would pass). */
    private fun toKotlin(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().apply { for ((k, v) in element) put(k, toKotlin(v)) }
        is JsonArray -> element.map { toKotlin(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" || element.content == "false" -> element.content.toBoolean()
            else -> element.content.toDouble()
        }
    }

    // Keep ConsentInput import used for readers of this file: the vector's consent is a raw ConsentInput JSON.
    @Suppress("unused")
    private val consentType: Class<ConsentInput> = ConsentInput::class.java
}
