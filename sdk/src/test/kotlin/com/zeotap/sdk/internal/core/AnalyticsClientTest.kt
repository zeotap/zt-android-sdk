package com.zeotap.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.zeotap.sdk.Callback
import com.zeotap.sdk.CmpMode
import com.zeotap.sdk.Configuration
import com.zeotap.sdk.ConsentDefault
import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentOptions
import com.zeotap.sdk.ConsentSource
import com.zeotap.sdk.DropReason
import com.zeotap.sdk.SdkException
import com.zeotap.sdk.SdkListener
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Mirror of `sdk/js/src/client.test.ts` — wiring, payload shape, reset/flush,
 * and the consent-gating scenarios — run through the real engine over fakes on
 * a virtual-time dispatcher.
 */
class AnalyticsClientTest {
    private class RecordingCallback : Callback {
        val calls = mutableListOf<Throwable?>()
        override fun invoke(error: Throwable?) {
            calls.add(error)
        }
    }

    private fun gated(build: ConsentOptions.Builder.() -> Unit = {}): Configuration =
        ClientHarness.baseConfig {
            consent(ConsentOptions.Builder().require(true).cmp(CmpMode.NONE).apply(build).build())
        }

    private fun harness(config: Configuration = ClientHarness.baseConfig(), storage: com.zeotap.sdk.internal.testing.InMemoryStorage = ClientHarness.seededStorage()) =
        ClientHarness(config, storage = storage)

    private val JsonObject.type get() = this["type"]!!.jsonPrimitive.content
    private val JsonObject.event get() = this["event"]?.jsonPrimitive?.content
    private val JsonObject.userId get() = this["userId"]?.jsonPrimitive?.content
    private val JsonObject.context get() = this["context"]!!.jsonObject

    // ---- payload shape --------------------------------------------------

    @Test
    fun `track builds the base payload in wire order with event and properties`() {
        val h = harness()
        h.client.track("Button Clicked", mapOf("label" to "signup", "count" to 2.0), null)
        h.flushAndSettle()
        val p = h.sentEvents().single()
        assertThat(p.keys.toList()).isEqualTo(listOf("messageId", "type", "anonymousId", "context", "timestamp", "sentAt", "event", "properties"))
        assertThat(p["messageId"]!!.jsonPrimitive.content).isEqualTo("00000000-0000-4000-8000-000000000001")
        assertThat(p["anonymousId"]!!.jsonPrimitive.content).isEqualTo(FIXED_ANON)
        assertThat(p["timestamp"]!!.jsonPrimitive.content).isEqualTo("2026-01-15T10:20:30.123Z")
        assertThat(p["sentAt"]).isEqualTo(p["timestamp"])
        assertThat(p["properties"]!!.jsonObject["count"]!!.jsonPrimitive.content).isEqualTo("2")
        val library = p.context["library"]!!.jsonObject
        assertThat(library["name"]!!.jsonPrimitive.content).isEqualTo("@zeotap/android-sdk")
        assertThat(library["version"]!!.jsonPrimitive.content).isEqualTo("0.0.0-test")
        assertThat(p.context.keys.first()).isEqualTo("library")
        assertThat(p.context.keys).containsAtLeast("app", "device", "os", "network", "screen", "locale", "timezone", "userAgent")
    }

    @Test
    fun `empty event, groupId and newId are silent no-ops`() {
        val h = harness()
        val cb = RecordingCallback()
        h.client.track("", null, cb)
        h.client.group("", null, cb)
        h.client.alias("", null, cb)
        h.flushAndSettle()
        assertThat(h.sentEvents()).isEmpty()
        assertThat(cb.calls).isEmpty()
    }

    @Test
    fun `identify persists the user id and later events carry it after sentAt`() {
        val h = harness()
        h.client.identify("user-1", mapOf("plan" to "pro"), null)
        h.client.track("After", null, null)
        h.flushAndSettle()
        val (identify, track) = h.sentEvents()
        assertThat(identify.keys.toList()).isEqualTo(listOf("messageId", "type", "anonymousId", "context", "timestamp", "sentAt", "userId", "traits"))
        assertThat(track.keys.toList()).isEqualTo(listOf("messageId", "type", "anonymousId", "context", "timestamp", "sentAt", "userId", "event"))
        assertThat(track.userId).isEqualTo("user-1")
        assertThat(h.storage.get("zeotap_user_id")).isEqualTo("user-1")
        assertThat(h.client.userId()).isEqualTo("user-1")
    }

    @Test
    fun `screen group and alias shapes`() {
        val h = harness()
        h.client.screen("Shop", "Product", mapOf("sku" to "A"), null)
        h.client.group("acme", mapOf("plan" to "enterprise"), null)
        h.client.alias("new-id", null, null)
        h.flushAndSettle()
        val (screen, group, alias) = h.sentEvents()
        assertThat(screen.keys.toList()).containsAtLeast("category", "name", "properties").inOrder()
        assertThat(group["groupId"]!!.jsonPrimitive.content).isEqualTo("acme")
        assertThat(alias.userId).isEqualTo("new-id")
        assertThat(alias["previousId"]!!.jsonPrimitive.content).isEqualTo(FIXED_ANON)
    }

    @Test
    fun `integration is stamped only when both fields are present`() {
        val h = harness(ClientHarness.baseConfig { integration("react-native-sdk", "1.2.3") })
        h.client.track("Ev", null, null)
        h.flushAndSettle()
        val library = h.sentEvents().single().context["library"]!!.jsonObject
        assertThat(library["integration"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("react-native-sdk")
        val h2 = harness(ClientHarness.baseConfig { integration("only-name", "") })
        h2.client.track("Ev", null, null)
        h2.flushAndSettle()
        assertThat(h2.sentEvents().single().context["library"]!!.jsonObject.containsKey("integration")).isFalse()
    }

    @Test
    fun `consent and identities are attached at enqueue`() {
        val h = harness()
        h.client.setConsent(ConsentInput.Parsed(analytics = true, advertising = false))
        h.client.setUserIdentities(mapOf("fpuid" to "fp-1", "email" to "a@b.com"), false, null)
        h.client.track("Ev", null, null) // same tick as setUserIdentities
        h.flushAndSettle()
        val ctx = h.sentEvents().single().context
        assertThat(ctx["consent"]!!.jsonObject.keys.toList()).isEqualTo(listOf("analytics", "advertising"))
        val identities = ctx["identities"]!!.jsonObject
        assertThat(identities["fpuid"]!!.jsonPrimitive.content).isEqualTo("fp-1")
        assertThat(identities["email"]!!.jsonObject.keys.toList()).isEqualTo(listOf("sha256_lowercase", "sha256_uppercase", "sha1_lowercase", "sha1_uppercase"))
    }

    // ---- queue + flush ----------------------------------------------------

    @Test
    fun `auto-flushes at flushAt and on the interval timer`() {
        val h = harness(ClientHarness.baseConfig { flushAt(2); flushIntervalMs(5_000) })
        h.client.track("One", null, null)
        h.settle()
        assertThat(h.http.requests).isEmpty()
        h.client.track("Two", null, null)
        h.settle()
        assertThat(h.sentEvents().map { it.event }).containsExactly("One", "Two")
        h.client.track("Three", null, null)
        h.settle()
        assertThat(h.http.requests).hasSize(1)
        h.advance(5_000)
        assertThat(h.sentEvents().map { it.event }).containsExactly("One", "Two", "Three")
    }

    @Test
    fun `exhausted retries release the batch and back off, success resets`() {
        val h = harness()
        h.http.nextStatus = 503
        h.client.track("Ev", null, null)
        h.flushAndSettle()
        h.advance(500); h.advance(1000); h.advance(2000)
        assertThat(h.http.requests).hasSize(4)
        assertThat(h.queue.size()).isEqualTo(1) // released, not dropped
        // Timer flush within the backoff window is suppressed…
        h.advance(5_000)
        assertThat(h.http.requests).hasSize(4)
        // …but a manual flush forces it, and success drains the queue.
        h.http.nextStatus = 200
        h.flushAndSettle()
        assertThat(h.http.requests).hasSize(5)
        assertThat(h.queue.size()).isEqualTo(0)
        assertThat(h.sentEvents().map { it["messageId"]!!.jsonPrimitive.content }.toSet()).hasSize(1) // same messageId every attempt
    }

    @Test
    fun `non-retryable 4xx drops the batch and notifies the listener`() {
        val h = harness()
        var failure: Pair<Int?, Int>? = null
        h.client.addListener(object : SdkListener {
            override fun onDeliveryFailure(status: Int?, attempts: Int, error: Throwable?) { failure = status to attempts }
        })
        h.http.nextStatus = 401
        h.client.track("Ev", null, null)
        h.flushAndSettle()
        assertThat(h.http.requests).hasSize(1)
        assertThat(h.queue.size()).isEqualTo(0)
        assertThat(failure).isEqualTo(401 to 1)
    }

    @Test
    fun `partial failure surfaces through onBatchResult`() {
        val h = harness()
        val results = mutableListOf<com.zeotap.sdk.BatchResult>()
        h.client.addListener(object : SdkListener {
            override fun onBatchResult(result: com.zeotap.sdk.BatchResult) { results.add(result) }
        })
        h.http.nextBody = """{"success":false,"received":0,"errors":["event 0: missing type"]}"""
        h.client.track("Ev", null, null)
        h.flushAndSettle()
        assertThat(results.single().success).isFalse()
        assertThat(results.single().errors).containsExactly("event 0: missing type")
    }

    @Test
    fun `reset rotates the anonymous id and clears everything`() {
        val h = harness(gated())
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.client.identify("user-1", null, null)
        h.client.setUserIdentities(mapOf("fpuid" to "fp-1"), false, null)
        h.settle()
        val before = h.client.anonymousId()
        h.client.reset()
        h.settle()
        assertThat(h.client.anonymousId()).isNotEqualTo(before)
        assertThat(h.client.userId()).isNull()
        assertThat(h.storage.get("zeotap_user_identities")).isNull()
        assertThat(h.storage.get("zeotap_consent")).isNull()
        assertThat(h.client.getConsentState().resolved).isFalse()
        assertThat(h.queue.size()).isEqualTo(0)
        // The default timer is re-armed: gating resolves with the default after cmpTimeoutMs.
        h.client.track("Later", null, null)
        h.advance(10_000)
        h.flushAndSettle()
        assertThat(h.sentEvents().map { it.event }).contains("Later")
    }

    // ---- consent gating (client.test.ts "consent gating") -------------------

    @Test
    fun `default config flows immediately even with a denying consent attached`() {
        val h = harness()
        h.client.setConsent(ConsentInput.Parsed(analytics = false))
        val cb = RecordingCallback()
        h.client.track("Ev", null, cb)
        h.flushAndSettle()
        assertThat(h.sentEvents().single().context["consent"]!!.jsonObject["analytics"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(cb.calls).containsExactly(null)
    }

    @Test
    fun `require buffers then replays FIFO on consent`() {
        val h = harness(gated())
        h.client.track("First", null, null)
        h.client.track("Second", null, null)
        h.flushAndSettle()
        assertThat(h.sentEvents()).isEmpty()
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.flushAndSettle()
        assertThat(h.sentEvents().map { it.event }).containsExactly("First", "Second").inOrder()
    }

    @Test
    fun `buffer cap drops the newest with an error callback`() {
        val h = harness(gated { maxBufferedEvents(2) })
        val dropped = RecordingCallback()
        val drops = mutableListOf<DropReason>()
        h.client.addListener(object : SdkListener {
            override fun onEventDropped(type: String, reason: DropReason, message: String) { drops.add(reason) }
        })
        h.client.track("A", null, null)
        h.client.track("B", null, null)
        h.client.track("C", null, dropped)
        h.settle()
        assertThat(dropped.calls.single()).isInstanceOf(SdkException::class.java)
        assertThat(dropped.calls.single()!!.message).isEqualTo("[SDK] Event dropped: pre-consent buffer full")
        assertThat(drops).containsExactly(DropReason.BUFFER_FULL)
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.flushAndSettle()
        assertThat(h.sentEvents().map { it.event }).containsExactly("A", "B")
    }

    @Test
    fun `default timeout resolves granted and replays`() {
        val h = harness(gated { cmpTimeoutMs(5_000) })
        h.client.track("Ev", null, null)
        h.advance(4_999)
        h.flushAndSettle()
        assertThat(h.sentEvents()).isEmpty()
        h.advance(1)
        h.flushAndSettle()
        assertThat(h.sentEvents().map { it.event }).containsExactly("Ev")
        assertThat(h.client.getConsentState().source).isEqualTo(ConsentSource.DEFAULT)
    }

    @Test
    fun `default timeout resolves denied and drops with an error`() {
        val h = harness(gated { default(ConsentDefault.DENIED); cmpTimeoutMs(5_000) })
        val cb = RecordingCallback()
        h.client.track("Ev", null, cb)
        h.advance(5_000)
        h.flushAndSettle()
        assertThat(h.sentEvents()).isEmpty()
        assertThat(cb.calls.single()!!.message).isEqualTo("[SDK] Dropped track: no track consent")
    }

    @Test
    fun `default denied alone activates gating`() {
        val h = harness(ClientHarness.baseConfig { consent(ConsentOptions.Builder().default(ConsentDefault.DENIED).cmp(CmpMode.NONE).build()) })
        h.client.track("Ev", null, null)
        h.flushAndSettle()
        assertThat(h.sentEvents()).isEmpty()
    }

    @Test
    fun `userId persistence is deferred until identify consent is granted`() {
        val h = harness(gated())
        h.client.identify("user-1", null, null)
        h.settle()
        assertThat(h.storage.get("zeotap_user_id")).isNull()
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.settle()
        assertThat(h.storage.get("zeotap_user_id")).isEqualTo("user-1")
    }

    @Test
    fun `userId is never persisted when identify resolves denied`() {
        val h = harness(gated { default(ConsentDefault.DENIED); cmpTimeoutMs(1_000) })
        h.client.identify("user-1", null, null)
        h.advance(1_000)
        h.flushAndSettle()
        assertThat(h.storage.get("zeotap_user_id")).isNull()
        assertThat(h.sentEvents()).isEmpty()
    }

    @Test
    fun `buffered events gain the userId once a granted identify applies`() {
        val h = harness(gated())
        h.client.identify("user-1", null, null)
        h.client.track("After Identify", null, null)
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.flushAndSettle()
        val track = h.sentEvents().last()
        assertThat(track.event).isEqualTo("After Identify")
        assertThat(track.userId).isEqualTo("user-1")
    }

    @Test
    fun `reset clears the pre-consent buffer`() {
        val h = harness(gated())
        h.client.track("Buffered", null, null)
        h.client.reset()
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.flushAndSettle()
        assertThat(h.sentEvents()).isEmpty()
    }

    @Test
    fun `replayed events carry the consent they were released under`() {
        val h = harness(gated())
        h.client.track("Held", null, null)
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.flushAndSettle()
        assertThat(h.sentEvents().single().context["consent"]!!.jsonObject["analytics"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun `manual denial drops track and identify with their own messages`() {
        val h = harness(gated())
        h.client.setConsent(ConsentInput.Parsed(analytics = false))
        val a = RecordingCallback()
        val b = RecordingCallback()
        h.client.track("Ev", null, a)
        h.client.identify("u1", null, b)
        h.settle()
        assertThat(a.calls.single()!!.message).isEqualTo("[SDK] Dropped track: no track consent")
        assertThat(b.calls.single()!!.message).isEqualTo("[SDK] Dropped identify: no identify consent")
    }

    @Test
    fun `a manual resolution persists and hydrates on reload as source storage`() {
        val storage = ClientHarness.seededStorage()
        val h = harness(gated(), storage)
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.settle()
        assertThat(storage.get("zeotap_consent")).contains("\"source\":\"manual\"")
        h.client.shutdown()
        val h2 = harness(gated(), storage)
        assertThat(h2.client.getConsentState().source).isEqualTo(ConsentSource.STORAGE)
        val writesBefore = storage.writes
        h2.client.track("After", null, null)
        h2.flushAndSettle()
        assertThat(h2.sentEvents().map { it.event }).containsExactly("After")
        // Hydration is not re-persisted.
        assertThat(storage.writes).isEqualTo(writesBefore)
    }

    @Test
    fun `persist false writes nothing`() {
        val h = harness(gated { persist(false) })
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.settle()
        assertThat(h.storage.get("zeotap_consent")).isNull()
    }

    @Test
    fun `getConsentState reflects the resolution`() {
        val h = harness(gated())
        assertThat(h.client.getConsentState().resolved).isFalse()
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.settle()
        val state = h.client.getConsentState()
        assertThat(state.resolved).isTrue()
        assertThat(state.signals!!.track && state.signals!!.identify && state.signals!!.cookieSync).isFalse()
        assertThat(state.signals!!.track).isTrue()
        assertThat(state.source).isEqualTo(ConsentSource.MANUAL)
    }

    // ---- identities gating ----------------------------------------------------

    @Test
    fun `setUserIdentities buffers under gating and applies when granted`() {
        val h = harness(gated())
        val cb = RecordingCallback()
        h.client.setUserIdentities(mapOf("fpuid" to "fp-1"), false, cb)
        h.settle()
        assertThat(h.storage.get("zeotap_user_identities")).isNull()
        assertThat(cb.calls).isEmpty()
        h.client.track("Ev", null, null)
        h.client.setConsent(ConsentInput.Parsed(analytics = true))
        h.flushAndSettle()
        assertThat(cb.calls).containsExactly(null)
        assertThat(h.sentEvents().single().context["identities"]!!.jsonObject["fpuid"]!!.jsonPrimitive.content).isEqualTo("fp-1")
    }

    @Test
    fun `setUserIdentities is dropped with an error when identify is denied`() {
        val h = harness(gated { default(ConsentDefault.DENIED); cmpTimeoutMs(1_000) })
        val cb = RecordingCallback()
        h.client.setUserIdentities(mapOf("fpuid" to "fp-1"), false, cb)
        h.advance(1_000)
        assertThat(cb.calls.single()!!.message).isEqualTo("[SDK] Dropped identity update: no identify consent")
        val late = RecordingCallback()
        h.client.setUserIdentities(mapOf("fpuid" to "fp-2"), false, late)
        h.settle()
        assertThat(late.calls.single()!!.message).isEqualTo("[SDK] Identities dropped: no identify consent")
    }

    @Test
    fun `unsetUserIdentities is never gated and reset clears identities`() {
        val h = harness(ClientHarness.baseConfig())
        h.client.setUserIdentities(mapOf("fpuid" to "fp-1", "crm" to "x"), false, null)
        h.settle()
        h.client.unsetUserIdentities(listOf("crm"))
        h.settle()
        assertThat(h.storage.get("zeotap_user_identities")).isEqualTo("""{"fpuid":"fp-1"}""")
        h.client.reset()
        h.settle()
        assertThat(h.storage.get("zeotap_user_identities")).isNull()
    }
}
