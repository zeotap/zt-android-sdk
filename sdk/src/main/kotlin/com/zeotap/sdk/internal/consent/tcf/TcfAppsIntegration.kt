package com.zeotap.sdk.internal.consent.tcf

import com.zeotap.sdk.ConsentOptions
import com.zeotap.sdk.ConsentSignals
import com.zeotap.sdk.ConsentSource
import com.zeotap.sdk.Logger
import com.zeotap.sdk.internal.consent.ConsentManager
import com.zeotap.sdk.internal.consent.ResolvedConsent
import com.zeotap.sdk.internal.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

/**
 * The web `__tcfapi` timing model (`tcf.ts` `TcfIntegration`) mapped onto the `IABTCF_*` preferences:
 * poll for a CMP every second within the budget (never found → silent, the client's default timer
 * decides); found → `onDetected()`, observe changes (debounced 250 ms) and evaluate: `gdprApplies == 0`
 * grants everything without a TC string and stops observing; a TC string with purpose consents
 * translates (and re-translates on later changes); a present-but-silent CMP is given one more budget
 * window, then resolved by `gdprApplies` (fail closed under GDPR). Every offer has source CMP.
 * All work runs on [scope]; pref-change callbacks hop onto it.
 */
internal class TcfAppsIntegration(
    private val defaults: TcfDefaults,
    private val consent: ConsentManager,
    private val options: ConsentOptions,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val logger: Logger,
    private val onDetected: () -> Unit,
) {
    private var pollJob: Job? = null
    private var pingJob: Job? = null
    private var debounceJob: Job? = null
    private var observer: AutoCloseable? = null
    private var pingArmed = false

    @Volatile
    private var stopped = false

    /** Begin CMP detection (1 s polling within `cmpTimeoutMs`). */
    fun start() {
        val maxAttempts = max(1, ceil(options.cmpTimeoutMs / POLL_INTERVAL_MS.toDouble()).toInt())
        pollJob = scope.launch { poll(maxAttempts) }
    }

    /** Cancel polling, the ping backstop and any pending re-evaluation; stop observing. */
    fun stop() {
        stopped = true
        pollJob?.cancel()
        pingJob?.cancel()
        debounceJob?.cancel()
        closeObserver()
    }

    private suspend fun poll(maxAttempts: Int) {
        var attempts = 0
        while (!stopped) {
            if (defaults.read().cmpPresent) {
                detected()
                return
            }
            attempts++
            if (attempts >= maxAttempts) {
                logger.debug("[SDK] No TCF CMP found within budget")
                return // client's default timer decides
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun detected() {
        logger.debug("[SDK] TCF CMP detected")
        onDetected()
        observer = defaults.observe { onPrefsChanged() }
        evaluate()
    }

    /** Invoked on the preference framework's thread — hop onto the SDK scope and debounce. */
    private fun onPrefsChanged() {
        if (stopped) return
        scope.launch {
            debounceJob?.cancel()
            debounceJob = launch {
                delay(DEBOUNCE_MS)
                evaluate()
            }
        }
    }

    private fun evaluate() {
        if (stopped) return
        val data = defaults.read()
        when {
            data.gdprApplies == false -> {
                // GDPR does not apply — everything is granted; the TC string is irrelevant.
                cancelPing()
                debounceJob?.cancel()
                closeObserver()
                offer(ConsentSignals.uniform(true), tcfString = null, gdprApplies = false)
            }
            isAnswered(data) -> {
                // Resolved now or previously. The observer stays so a later consent change re-resolves
                // (offer() accepts same-source re-resolutions).
                cancelPing()
                offer(TcfTranslator.translate(data, options), tcfString = data.tcString, gdprApplies = true)
            }
            else -> {
                logger.debug("[SDK] TCF pending")
                armPing()
            }
        }
    }

    private fun isAnswered(data: TcData): Boolean = !data.tcString.isNullOrEmpty() && data.purposeConsents != null

    /** Fail-closed backstop, armed once: a CMP that is present but never answers within another budget window. */
    private fun armPing() {
        if (pingArmed) return
        pingArmed = true
        pingJob = scope.launch {
            delay(options.cmpTimeoutMs)
            ping()
        }
    }

    private fun ping() {
        if (stopped) return
        val data = defaults.read()
        if (data.gdprApplies == false || isAnswered(data)) {
            evaluate()
        } else {
            logger.debug("[SDK] TCF CMP never answered; resolving by gdprApplies")
            offer(ConsentSignals.uniform(data.gdprApplies == false), tcfString = null, gdprApplies = data.gdprApplies)
        }
    }

    private fun offer(signals: ConsentSignals, tcfString: String?, gdprApplies: Boolean?) {
        consent.offer(
            ResolvedConsent(
                signals = signals,
                input = null,
                tcfString = tcfString,
                gdprApplies = gdprApplies,
                source = ConsentSource.CMP,
                ts = clock.nowMs(),
            ),
        )
        logger.debug(
            "[SDK] TCF consent resolved track=${signals.track} identify=${signals.identify} cookieSync=${signals.cookieSync}",
        )
    }

    private fun cancelPing() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun closeObserver() {
        runCatching { observer?.close() }
        observer = null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val DEBOUNCE_MS = 250L
    }
}
