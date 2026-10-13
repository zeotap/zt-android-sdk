package com.zeotap.sdk.internal.core

import com.zeotap.sdk.BatchResult
import com.zeotap.sdk.Callback
import com.zeotap.sdk.CmpMode
import com.zeotap.sdk.Configuration
import com.zeotap.sdk.ConsentDefault
import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentSource
import com.zeotap.sdk.ConsentStateSnapshot
import com.zeotap.sdk.DropReason
import com.zeotap.sdk.Logger
import com.zeotap.sdk.SdkException
import com.zeotap.sdk.SdkListener
import com.zeotap.sdk.internal.consent.ConsentManager
import com.zeotap.sdk.internal.consent.ConsentPersistence
import com.zeotap.sdk.internal.consent.ConsentState
import com.zeotap.sdk.internal.consent.ResolvedConsent
import com.zeotap.sdk.internal.consent.tcf.TcfAppsIntegration
import com.zeotap.sdk.internal.context.ManualAdvertisingIdProvider
import com.zeotap.sdk.internal.identity.IdentityStore
import com.zeotap.sdk.internal.identity.UserIdentityManager
import com.zeotap.sdk.internal.json.JsonMapper
import com.zeotap.sdk.internal.lifecycle.AppLifecycle
import com.zeotap.sdk.internal.lifecycle.LifecycleEvents
import com.zeotap.sdk.internal.lifecycle.NetworkMonitor
import com.zeotap.sdk.internal.queue.EventQueue
import com.zeotap.sdk.internal.transport.SendOutcome
import com.zeotap.sdk.internal.transport.Transport
import com.zeotap.sdk.internal.util.Clock
import com.zeotap.sdk.internal.util.IdGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The engine — a port of the browser SDK's `AnalyticsClient` (`client.ts`) onto
 * a single-threaded coroutine dispatcher. Every public method posts to
 * [SdkScope.scope]; all state below is touched only on that thread, except the
 * atomic snapshots served to other threads.
 *
 * Mobile divergences (all documented in sdk/conformance/README.md): the queue
 * is durable and reserved/acked around each send; exhausted retries RELEASE
 * the batch and back off instead of dropping; ingest's response body is
 * parsed and surfaced through [SdkListener]; hashing is synchronous, so the
 * web's identity barrier is unnecessary (ordering comes from the dispatcher).
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class AnalyticsClient(
    val writeKey: String,
    val config: Configuration,
    private val sdk: SdkScope,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val identity: IdentityStore,
    private val userIdentities: UserIdentityManager,
    private val consent: ConsentManager,
    private val consentPersistence: ConsentPersistence?,
    tcfFactory: ((onDetected: () -> Unit) -> TcfAppsIntegration)?,
    private val queue: EventQueue,
    private val transport: Transport,
    private val payloadBuilder: PayloadBuilder,
    private val lifecycle: AppLifecycle?,
    private val network: NetworkMonitor?,
    private val lifecycleEvents: LifecycleEvents?,
    private val logger: Logger,
    /**
     * Null only where a host cannot supply an advertising id — today just the
     * unit-test harnesses, since SdkFactory always wires one.
     */
    private val manualAdvertisingId: ManualAdvertisingIdProvider? = null,
) {
    private enum class Signal(val wire: String) { TRACK("track"), IDENTIFY("identify") }

    /** A call held in the pre-consent buffer until consent resolves (`BufferedEvent` in client.ts). */
    private class BufferedEvent(
        val draft: EventDraft?,
        val signal: Signal,
        val callback: Callback?,
        val apply: (() -> Unit)?,
    )

    private val scope get() = sdk.scope
    private val gatingActive = config.consent.gatingActive
    private val tcf: TcfAppsIntegration? = tcfFactory?.invoke { cancelConsentTimer() }

    private val preConsentBuffer = ArrayDeque<BufferedEvent>()
    private var consentTimer: Job? = null
    private var flushTimer: Job? = null
    private var inFlight = false
    private var backoffIndex = 0
    private var nextFlushAllowedAt = 0L
    private var started = false

    @Volatile
    private var shutdown = false

    private val listeners = CopyOnWriteArrayList<SdkListener>()
    private val consentSnapshot = AtomicReference(ConsentStateSnapshot.UNRESOLVED)
    private val anonymousIdSnapshot = AtomicReference(identity.anonymousId())
    private val userIdSnapshot = AtomicReference(identity.userId())

    init {
        consent.onResolve { resolution -> onConsentResolved(resolution) }
    }

    // ---- lifecycle -------------------------------------------------------

    /** Wire timers/observers. Called once by the factory after construction. */
    fun start() {
        scope.launch {
            if (started) return@launch
            started = true
            runCatching { queue.releaseAll() }.onFailure { reportError(it) }

            // Hydrate a persisted decision BEFORE arming timers/CMP detection —
            // a stronger source simply overrides it later.
            consentPersistence?.load()?.let { stored ->
                consent.offer(stored)
                logger.debug("[SDK] Consent hydrated from storage")
            }
            scheduleConsentDefault()
            if (config.consent.cmp == CmpMode.AUTO) tcf?.start()

            flushTimer = scope.launch {
                while (isActive) {
                    delay(config.flushIntervalMs)
                    doFlush(force = false)
                }
            }
            network?.start {
                scope.launch {
                    resetBackoff()
                    doFlush(force = true)
                }
            }
            lifecycle?.start(
                object : AppLifecycle.Listener {
                    override fun onForeground() {
                        scope.launch {
                            resetBackoff()
                            lifecycleEvents?.onForeground()?.let { track(it.name, it.properties, null) }
                            doFlush(force = true)
                        }
                    }

                    override fun onBackground() {
                        scope.launch {
                            lifecycleEvents?.onBackground()?.let { track(it.name, it.properties, null) }
                            if (config.flushOnBackground) doFlush(force = true)
                        }
                    }
                },
            )
            lifecycleEvents?.onLaunch()?.forEach { track(it.name, it.properties, null) }
            logger.debug("[SDK] Initialized apiHost=${config.apiHost} flushAt=${config.flushAt} flushInterval=${config.flushIntervalMs}")
        }
    }

    /**
     * Stop everything. Cancelling the scope cancels the flush/consent timers and
     * any pending posts (a launched cleanup coroutine would itself be cancelled
     * before running, so the observers and the queue are closed directly).
     */
    fun shutdown() {
        shutdown = true
        sdk.close()
        runCatching { tcf?.stop() }
        runCatching { lifecycle?.stop() }
        runCatching { network?.stop() }
        runCatching { queue.close() }
    }

    // ---- public surface (posted to the SDK thread) -------------------------

    fun identify(userId: String?, traits: Map<String, Any?>?, callback: Callback?) {
        val traitsJson = traits?.let(JsonMapper::fromMap)
        scope.launch {
            val draft = build("identify")
            if (!userId.isNullOrEmpty()) draft.fields["userId"] = JsonPrimitive(userId)
            if (traitsJson != null) draft.fields["traits"] = traitsJson
            val apply = if (!userId.isNullOrEmpty()) ({ setUserId(userId) }) else null
            submit(draft, Signal.IDENTIFY, callback, apply)
        }
    }

    fun track(event: String, properties: Map<String, Any?>?, callback: Callback?) {
        if (event.isEmpty()) return
        val propsJson = properties?.let(JsonMapper::fromMap)
        scope.launch {
            val draft = build("track")
            draft.fields["event"] = JsonPrimitive(event)
            if (propsJson != null) draft.fields["properties"] = propsJson
            submit(draft, Signal.TRACK, callback, null)
        }
    }

    fun screen(category: String?, name: String?, properties: Map<String, Any?>?, callback: Callback?) {
        val propsJson = properties?.let(JsonMapper::fromMap)
        scope.launch {
            val draft = build("screen")
            if (!category.isNullOrEmpty()) draft.fields["category"] = JsonPrimitive(category)
            if (!name.isNullOrEmpty()) draft.fields["name"] = JsonPrimitive(name)
            if (propsJson != null) draft.fields["properties"] = propsJson
            submit(draft, Signal.TRACK, callback, null)
        }
    }

    fun group(groupId: String, traits: Map<String, Any?>?, callback: Callback?) {
        if (groupId.isEmpty()) return
        val traitsJson = traits?.let(JsonMapper::fromMap)
        scope.launch {
            val draft = build("group")
            draft.fields["groupId"] = JsonPrimitive(groupId)
            if (traitsJson != null) draft.fields["traits"] = traitsJson
            submit(draft, Signal.TRACK, callback, null)
        }
    }

    fun alias(newId: String, previousId: String?, callback: Callback?) {
        if (newId.isEmpty()) return
        scope.launch {
            val draft = build("alias")
            draft.fields["userId"] = JsonPrimitive(newId)
            draft.fields["previousId"] = JsonPrimitive(previousId ?: identity.anonymousId())
            submit(draft, Signal.TRACK, callback, null)
        }
    }

    fun setConsent(input: ConsentInput) {
        scope.launch { consent.setConsent(input) }
    }

    fun setUserIdentities(identities: Map<String, String?>, hashed: Boolean, callback: Callback?) {
        val copy = LinkedHashMap(identities)
        scope.launch {
            val launchOp = { launchIdentityOp(copy, hashed, callback) }
            if (!gatingActive) {
                launchOp()
                return@launch
            }
            if (!consent.isResolved()) {
                bufferEvent(BufferedEvent(null, Signal.IDENTIFY, callback, launchOp))
                return@launch
            }
            if (consent.getSignals()?.identify == true) {
                launchOp()
                return@launch
            }
            logger.warn("[SDK] Dropped setUserIdentities: no identify consent")
            val message = "[SDK] Identities dropped: no identify consent"
            notifyDropped("identity update", DropReason.CONSENT_DENIED, message)
            callback?.invoke(SdkException(message))
        }
    }

    fun unsetUserIdentities(keys: List<String>?) {
        scope.launch { userIdentities.unset(keys) }
    }

    fun getConsentState(): ConsentStateSnapshot = consentSnapshot.get()

    fun anonymousId(): String = anonymousIdSnapshot.get()

    fun userId(): String? = userIdSnapshot.get()

    fun reset() {
        scope.launch {
            identity.reset()
            anonymousIdSnapshot.set(identity.anonymousId())
            userIdSnapshot.set(null)
            userIdentities.clear()
            runCatching { queue.clear() }.onFailure { reportError(it) }
            preConsentBuffer.clear()
            consent.clear()
            consentSnapshot.set(ConsentStateSnapshot.UNRESOLVED)
            consentPersistence?.clear()
            // Re-arm so a gated client can't buffer forever after logout.
            scheduleConsentDefault()
        }
    }

    fun flush() {
        scope.launch { doFlush(force = true) }
    }

    fun setAdvertisingId(id: String?, limitAdTracking: Boolean) {
        val manual = manualAdvertisingId
        if (manual == null) {
            logger.warn("[SDK] setAdvertisingId is unavailable in this configuration")
            return
        }
        manual.set(id, limitAdTracking)
        // A warning, not a failure: `collectAdvertisingId` is the host's single
        // switch for "may I collect this at all", and one call must not override
        // it. But a SILENT no-op here is the legacy behaviour that let an app
        // believe it had set an id that was never sent, so it is said out loud.
        if (!config.collectAdvertisingId) {
            logger.warn(
                "[SDK] setAdvertisingId was called but Configuration.collectAdvertisingId is " +
                    "false, so no advertising id is attached to events. Set it to true to " +
                    "enable collection.",
            )
        }
    }

    fun addListener(listener: SdkListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SdkListener) {
        listeners.remove(listener)
    }

    // ---- consent gate (client.ts 669-762) ----------------------------------

    private fun build(type: String): EventDraft {
        val draft = payloadBuilder.base(type, ids.uuid(), identity.anonymousId(), identity.userId(), clock.nowMs())
        consent.getConsent()?.let { draft.context["consent"] = ConsentState.toJson(it) }
        return draft
    }

    private fun setUserId(userId: String) {
        identity.setUserId(userId)
        userIdSnapshot.set(userId)
    }

    private fun submit(draft: EventDraft, signal: Signal, callback: Callback?, apply: (() -> Unit)?) {
        if (!gatingActive) {
            apply?.invoke()
            enqueue(draft, callback)
            return
        }
        val entry = BufferedEvent(draft, signal, callback, apply)
        if (!consent.isResolved()) {
            bufferEvent(entry)
            return
        }
        dispatchGated(entry)
    }

    private fun bufferEvent(entry: BufferedEvent) {
        val what = entry.draft?.type ?: "identity update"
        if (preConsentBuffer.size >= config.consent.maxBufferedEvents) {
            logger.warn("[SDK] Dropped $what: pre-consent buffer full (${config.consent.maxBufferedEvents})")
            val message = "[SDK] Event dropped: pre-consent buffer full"
            notifyDropped(what, DropReason.BUFFER_FULL, message)
            entry.callback?.invoke(SdkException(message))
            return
        }
        preConsentBuffer.addLast(entry)
        logger.debug("[SDK] Buffered $what until consent resolves")
    }

    private fun dispatchGated(entry: BufferedEvent) {
        val signals = consent.getSignals()
        val granted = when (entry.signal) {
            Signal.TRACK -> signals?.track == true
            Signal.IDENTIFY -> signals?.identify == true
        }
        if (granted) {
            entry.apply?.invoke()
            // Pure side-effect entries (buffered setUserIdentities) own their callback.
            val draft = entry.draft ?: return
            // A buffered event built before a granted identify was applied misses
            // the userId it would have carried in the ungated flow — patch it in.
            if (!draft.fields.containsKey("userId")) {
                identity.userId()?.let { draft.fields["userId"] = JsonPrimitive(it) }
            }
            enqueue(draft, entry.callback)
            return
        }
        val what = entry.draft?.type ?: "identity update"
        val message = "[SDK] Dropped $what: no ${entry.signal.wire} consent"
        logger.warn(message)
        notifyDropped(what, DropReason.CONSENT_DENIED, message)
        entry.callback?.invoke(SdkException(message))
    }

    private fun drainConsentBuffer() {
        if (preConsentBuffer.isEmpty()) return
        val entries = ArrayList(preConsentBuffer)
        preConsentBuffer.clear()
        for (entry in entries) dispatchGated(entry)
    }

    private fun onConsentResolved(resolution: ResolvedConsent) {
        consentSnapshot.set(
            ConsentStateSnapshot(
                resolved = true,
                signals = resolution.signals,
                source = resolution.source,
                gdprApplies = resolution.gdprApplies,
            ),
        )
        drainConsentBuffer()
        if (resolution.source != ConsentSource.STORAGE) {
            runCatching { consentPersistence?.save(resolution) }.onFailure { reportError(it) }
        }
    }

    private fun scheduleConsentDefault() {
        if (!gatingActive) return
        consentTimer?.cancel()
        consentTimer = scope.launch {
            delay(config.consent.cmpTimeoutMs)
            consent.resolveDefault(config.consent.default == ConsentDefault.GRANTED)
        }
    }

    private fun cancelConsentTimer() {
        scope.launch { consentTimer?.cancel() }
    }

    // ---- identities --------------------------------------------------------

    private fun launchIdentityOp(identities: Map<String, String?>, hashed: Boolean, callback: Callback?) {
        try {
            userIdentities.setIdentities(identities, hashed)
            callback?.invoke(null)
        } catch (t: Throwable) {
            logger.error("[SDK] setUserIdentities failed", t)
            reportError(t)
            callback?.invoke(t)
        }
    }

    // ---- queue + delivery --------------------------------------------------

    private fun enqueue(draft: EventDraft, callback: Callback?) {
        try {
            userIdentities.get()?.let { draft.context["identities"] = it }
            // Events built BEFORE consent resolved (buffered) would otherwise
            // ship without context.consent even though they were only released
            // because consent was granted — attach the current state when missing.
            if (!draft.context.containsKey("consent")) {
                consent.getConsent()?.let { draft.context["consent"] = ConsentState.toJson(it) }
            }
            val json: JsonObject = draft.materialise()
            queue.enqueue(draft.messageId, JsonMapper.encode(json))
            logger.debug("[SDK] Enqueued ${draft.type}")
            if (queue.size() >= config.flushAt) scope.launch { doFlush(force = false) }
            callback?.invoke(null)
        } catch (t: Throwable) {
            logger.error("[SDK] Enqueue failed", t)
            notifyDropped(draft.type, DropReason.ENQUEUE_FAILED, t.message ?: "enqueue failed")
            reportError(t)
            callback?.invoke(t)
        }
    }

    private suspend fun doFlush(force: Boolean) {
        if (shutdown || inFlight) return
        if (!force && clock.nowMs() < nextFlushAllowedAt) return
        val batchId = ids.uuid()
        val batch = try {
            queue.reserve(batchId, Transport.RESERVE_MAX_BYTES)
        } catch (t: Throwable) {
            reportError(t)
            return
        }
        if (batch.isEmpty()) return
        inFlight = true
        val outcome = try {
            withContext(sdk.io) { transport.send(batch.map { it.payload }) }
        } catch (t: Throwable) {
            SendOutcome.Exhausted(attempts = 1, lastStatus = null, error = t)
        } finally {
            inFlight = false
        }
        when (outcome) {
            is SendOutcome.Success -> {
                safeQueue { queue.ack(batchId) }
                resetBackoff()
                notifyBatch(outcome.result)
                if (!outcome.result.success) {
                    logger.warn("[SDK] Ingest accepted ${outcome.result.received}/${outcome.result.sent} events: ${outcome.result.errors}")
                } else {
                    logger.debug("[SDK] Sent ${batch.size} events")
                }
                // Keep draining while there is more than one batch's worth.
                if (safeSize() > 0) scope.launch { doFlush(force = true) }
            }
            is SendOutcome.Rejected -> {
                // Non-retryable client error: the server's answer is deterministic; drop (web parity).
                safeQueue { queue.ack(batchId) }
                logger.error("[SDK] Ingest API error ${outcome.status}: ${outcome.body}")
                for (l in listeners) runCatching { l.onDeliveryFailure(outcome.status, 1, null) }
                notifyDropped("batch", DropReason.INGEST_REJECTED, "Ingest API error ${outcome.status}")
            }
            is SendOutcome.Exhausted -> {
                safeQueue { queue.release(batchId) }
                val delayMs = BACKOFF_MS[minOf(backoffIndex, BACKOFF_MS.lastIndex)]
                backoffIndex++
                nextFlushAllowedAt = clock.nowMs() + delayMs
                logger.warn("[SDK] Failed to send after ${outcome.attempts} attempts; retrying in ${delayMs}ms")
                for (l in listeners) runCatching { l.onDeliveryFailure(outcome.lastStatus, outcome.attempts, outcome.error) }
            }
        }
    }

    private fun resetBackoff() {
        backoffIndex = 0
        nextFlushAllowedAt = 0L
    }

    private fun safeSize(): Int = try {
        queue.size()
    } catch (t: Throwable) {
        reportError(t)
        0
    }

    private inline fun safeQueue(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            reportError(t)
        }
    }

    // ---- listeners ---------------------------------------------------------

    private fun notifyBatch(result: BatchResult) {
        for (l in listeners) runCatching { l.onBatchResult(result) }
    }

    private fun notifyDropped(type: String, reason: DropReason, message: String) {
        for (l in listeners) runCatching { l.onEventDropped(type, reason, message) }
    }

    private fun reportError(t: Throwable) {
        for (l in listeners) runCatching { l.onError(t) }
    }

    private companion object {
        /** Inter-flush backoff after an exhausted send: 30s → 1m → 2m → 5m (reset on success / network / foreground). */
        val BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)
    }
}
