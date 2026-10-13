package com.zeotap.sdk.internal.core

import com.zeotap.sdk.Configuration
import com.zeotap.sdk.IntegrationMeta
import com.zeotap.sdk.internal.consent.ConsentManager
import com.zeotap.sdk.internal.consent.ConsentPersistence
import com.zeotap.sdk.internal.consent.tcf.TcfAppsIntegration
import com.zeotap.sdk.internal.consent.tcf.TcfDefaults
import com.zeotap.sdk.internal.context.ContextCollector
import com.zeotap.sdk.internal.identity.IdentityStore
import com.zeotap.sdk.internal.identity.UserIdentityManager
import com.zeotap.sdk.internal.queue.InMemoryEventQueue
import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.testing.FakeClock
import com.zeotap.sdk.internal.testing.FakeTcfDefaults
import com.zeotap.sdk.internal.testing.InMemoryStorage
import com.zeotap.sdk.internal.testing.RecordingLogger
import com.zeotap.sdk.internal.testing.SequenceIdGenerator
import com.zeotap.sdk.internal.transport.HttpClient
import com.zeotap.sdk.internal.transport.HttpResponse
import com.zeotap.sdk.internal.transport.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 2026-01-15T10:20:30.123Z — the instant every conformance vector is pinned to. */
internal const val FIXED_NOW_MS: Long = 1_768_472_430_123L
internal const val FIXED_ANON: String = "11111111-1111-4111-8111-111111111111"

/** Records every POST and answers 200 `{}` (or a scripted body/status). */
internal class CapturingHttpClient : HttpClient {
    class Request(val url: String, val headers: Map<String, String>, val body: String)

    val requests = mutableListOf<Request>()
    var nextStatus: Int = 200
    var nextBody: String = "{}"

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        requests.add(Request(url, headers, body.toString(Charsets.UTF_8)))
        return HttpResponse(nextStatus, nextBody)
    }

    /** Every event payload that reached ingest, in order. */
    fun sentEvents(): List<JsonObject> = requests.flatMap { r ->
        Json.parseToJsonElement(r.body).jsonObject["batch"]!!.jsonArray.map { it.jsonObject }
    }
}

/** Deterministic platform context so payload assertions are exact. */
internal object FakeContextCollector : ContextCollector {
    override fun collect(): List<Pair<String, JsonElement>> = listOf(
        "app" to JsonObject(linkedMapOf("name" to JsonPrimitive("Sample"), "version" to JsonPrimitive("1.0"), "build" to JsonPrimitive("1"), "namespace" to JsonPrimitive("com.example"))),
        "device" to JsonObject(linkedMapOf("manufacturer" to JsonPrimitive("Google"), "model" to JsonPrimitive("Pixel"), "type" to JsonPrimitive("android"))),
        "os" to JsonObject(linkedMapOf("name" to JsonPrimitive("Android"), "version" to JsonPrimitive("14"))),
        "network" to JsonObject(linkedMapOf("wifi" to JsonPrimitive(true), "cellular" to JsonPrimitive(false))),
        "screen" to JsonObject(linkedMapOf("width" to JsonPrimitive(1080), "height" to JsonPrimitive(2400), "density" to JsonPrimitive(2.625))),
        "locale" to JsonPrimitive("en-US"),
        "timezone" to JsonPrimitive("Europe/Berlin"),
        "userAgent" to JsonPrimitive("Dalvik/2.1.0"),
    )
}

/** Platform context keys the web SDK never sends (stripped before payload comparison). */
internal val PLATFORM_CONTEXT_KEYS = setOf("app", "device", "os", "network", "screen", "locale", "timezone", "userAgent")

/**
 * The mirror image: context the WEB SDK sends and the mobile SDKs do not, so the
 * vectors carry it and this SDK cannot be expected to reproduce it.
 *
 * `page` was always here in spirit — the browser's page block, which is why the
 * `page` conformance cases are tagged `platforms:["js"]`. `session` arrived later,
 * from the sessions workstream on `main`: the web SDK now stamps
 * `context.session` on every payload, the vectors are generated from the real web
 * SDK, and the mobile SDKs have no sessions yet. That is a DEFERRED FEATURE, not
 * a permanent divergence — when mobile sessions land, delete the entry and the
 * vectors become the contract again. Documented in `sdk/conformance/README.md`.
 */
internal val WEB_ONLY_CONTEXT_KEYS = setOf("page", "session")

/**
 * Builds an [AnalyticsClient] over fakes on a single virtual-time test
 * dispatcher (both the SDK and the IO dispatcher), mirroring how
 * `client.test.ts` pins uuid()/isoNow()/Date.now(). Never `advanceUntilIdle()`
 * — the flush timer is an infinite loop; use [settle] / [advance].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ClientHarness(
    val config: Configuration,
    val storage: InMemoryStorage = InMemoryStorage(),
    val clock: FakeClock = FakeClock(FIXED_NOW_MS),
    val scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
    val ids: SequenceIdGenerator = SequenceIdGenerator(),
    val http: CapturingHttpClient = CapturingHttpClient(),
    val tcfDefaults: FakeTcfDefaults? = null,
    val libraryName: String = "@zeotap/android-sdk",
    val libraryVersion: String = "0.0.0-test",
    integration: IntegrationMeta? = config.integration,
    autoStart: Boolean = true,
) {
    val dispatcher = StandardTestDispatcher(scheduler)
    val sdk = SdkScope(dispatcher, dispatcher)
    val logger = RecordingLogger()
    val keys = Keys(config.namespace)
    val queue = InMemoryEventQueue(config.maxQueueSize)
    val consent = ConsentManager(clock)
    val identity = IdentityStore(keys, storage, ids)
    val userIdentities = UserIdentityManager(keys, storage, logger)
    val transport = Transport(config.apiHost, "wk_test", config.nativeAppId, http, clock, logger)
    val client: AnalyticsClient = AnalyticsClient(
        writeKey = "wk_test",
        config = config,
        sdk = sdk,
        clock = clock,
        ids = ids,
        identity = identity,
        userIdentities = userIdentities,
        consent = consent,
        consentPersistence = if (config.consent.persist) ConsentPersistence(keys, storage, config.consent.expiryDays, clock) else null,
        tcfFactory = tcfDefaults?.let { defaults ->
            { onDetected: () -> Unit -> TcfAppsIntegration(defaults as TcfDefaults, consent, config.consent, sdk.scope, clock, logger, onDetected) }
        },
        queue = queue,
        transport = transport,
        payloadBuilder = PayloadBuilder(libraryName, libraryVersion, integration, FakeContextCollector),
        lifecycle = null,
        network = null,
        lifecycleEvents = null,
        logger = logger,
    )

    init {
        if (autoStart) {
            client.start()
            settle()
        }
    }

    /** Run everything currently runnable (no time advance). */
    fun settle() {
        scheduler.runCurrent()
    }

    /** Advance virtual time (and the fake clock) by [ms], running what becomes due. */
    fun advance(ms: Long) {
        clock.nowMs += ms
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** flush() + settle: every queued event reaches [http]. */
    fun flushAndSettle() {
        client.flush()
        settle()
    }

    fun sentEvents(): List<JsonObject> = http.sentEvents()

    companion object {
        fun seededStorage(anonymousId: String = FIXED_ANON, namespace: String = "zeotap", extra: Map<String, String> = emptyMap()): InMemoryStorage =
            InMemoryStorage().apply {
                set("${namespace}_anonymous_id", anonymousId)
                for ((k, v) in extra) set(k, v)
            }

        fun baseConfig(builder: Configuration.Builder.() -> Unit = {}): Configuration =
            Configuration.Builder("https://ingest.example").apply(builder).build()
    }
}
