package com.zeotap.sdk.sample

import android.app.Application
import android.util.Log
import com.zeotap.sdk.Analytics
import com.zeotap.sdk.BatchResult
import com.zeotap.sdk.CmpMode
import com.zeotap.sdk.Configuration
import com.zeotap.sdk.ConsentDefault
import com.zeotap.sdk.ConsentOptions
import com.zeotap.sdk.DropReason
import com.zeotap.sdk.SdkListener
import com.zeotap.sdk.Zeotap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hosts the SDK singleton and an in-memory log the UI renders. `loadSdk()`
 * builds the [Configuration] from [SampleConfig]; the launcher activity calls it
 * when `autoLoad` is set (Maestro / instrumentation) or the user taps Load.
 */
class SampleApplication : Application() {
    val log = CopyOnWriteArrayList<String>()
    private val logListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addLogListener(l: () -> Unit) {
        logListeners.add(l)
    }

    fun removeLogListener(l: () -> Unit) {
        logListeners.remove(l)
    }

    fun appendLog(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        log.add(0, "$stamp  $line")
        while (log.size > 200) log.removeAt(log.size - 1)
        Log.d("ZeotapSample", line)
        for (l in logListeners) l()
    }

    /** Idempotent: returns the existing instance after the first call. */
    fun loadSdk(): Analytics? {
        val cfg = SampleConfig.get(this)
        if (cfg.writeKey.isBlank()) {
            appendLog("writeKey missing — set it in Settings")
            return null
        }
        if (Zeotap.isInitialized()) return Zeotap.getInstance()
        val consent = ConsentOptions.Builder()
            .require(cfg.consentRequire)
            .default(if (cfg.consentDefault == "denied") ConsentDefault.DENIED else ConsentDefault.GRANTED)
            .cmpTimeoutMs(cfg.cmpTimeoutMs)
            .apply {
                when (cfg.cmp) {
                    "auto" -> cmp(CmpMode.AUTO)
                    "none" -> cmp(CmpMode.NONE)
                }
            }
            .build()
        val configuration = Configuration.Builder(cfg.apiHost)
            .namespace(cfg.namespace)
            .debug(true)
            .flushAt(cfg.flushAt)
            .flushIntervalMs(cfg.flushIntervalMs)
            .consent(consent)
            .trackApplicationLifecycleEvents(cfg.lifecycleEvents)
            .collectAdvertisingId(cfg.advertisingId)
            .build()
        val sdk = Zeotap.load(this, cfg.writeKey, configuration)
        sdk.addListener(
            object : SdkListener {
                override fun onBatchResult(result: BatchResult) {
                    appendLog("batch ${result.status} success=${result.success} received=${result.received}/${result.sent} errors=${result.errors}")
                }

                override fun onDeliveryFailure(status: Int?, attempts: Int, error: Throwable?) {
                    appendLog("delivery failure status=$status attempts=$attempts error=${error?.message}")
                }

                override fun onEventDropped(type: String, reason: DropReason, message: String) {
                    appendLog("dropped $type ($reason): $message")
                }

                override fun onError(error: Throwable) {
                    appendLog("error: $error")
                }
            },
        )
        appendLog("SDK loaded apiHost=${cfg.apiHost} require=${cfg.consentRequire} default=${cfg.consentDefault} anonymousId=${sdk.anonymousId}")
        return sdk
    }
}
