package com.zeotap.sdk.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zeotap.sdk.Analytics
import com.zeotap.sdk.Callback
import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.Zeotap

/**
 * One button per SDK method plus a live log. Every control carries a
 * stable `android:contentDescription`/id from e2e/mobile/TESTIDS.md so
 * Espresso, UiAutomator and Maestro drive the same screen.
 */
class MainActivity : AppCompatActivity() {
    private val app get() = application as SampleApplication
    private lateinit var status: TextView
    private lateinit var consentState: TextView
    private lateinit var logView: TextView
    private val onLog: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.txt_status)
        consentState = findViewById(R.id.txt_consent_state)
        logView = findViewById(R.id.txt_log)

        val cfg = SampleConfig.get(this)
        cfg.applyIntent(intent)
        if (cfg.autoLoad) app.loadSdk()

        val email = findViewById<EditText>(R.id.input_email)
        val userId = findViewById<EditText>(R.id.input_user_id)

        bind(R.id.btn_load) { app.loadSdk(); render() }
        bind(R.id.btn_settings) { startActivity(Intent(this, SettingsActivity::class.java)) }
        bind(R.id.btn_tcf_stub) { startActivity(Intent(this, TcfStubActivity::class.java)) }

        bind(R.id.btn_consent_accept) { sdk { it.setConsent(ConsentInput.Parsed(analytics = true, marketing = true, advertising = true, functional = true)) } }
        bind(R.id.btn_consent_reject) { sdk { it.setConsent(ConsentInput.Parsed(analytics = false, marketing = false, advertising = false, functional = false)) } }
        bind(R.id.btn_consent_tcf) { sdk { it.setConsent(ConsentInput.Tcf("CQAbcSampleTCString")) } }
        bind(R.id.btn_set_identities) {
            sdk { it.setUserIdentities(mapOf("email" to email.text.toString(), "cellno" to "91 9999999999", "fpuid" to "fp-sample"), false, cb("setUserIdentities")) }
        }
        bind(R.id.btn_unset_identities) { sdk { it.unsetUserIdentities() } }
        // A FIXED value, shared with iOS, RN and `e2e/helpers/mobile/expectations.ts`: the e2e
        // spec asserts `context.device.advertisingId` equals it exactly. The SDK's own lookup
        // yields nothing on an emulator (no Play Services) or a simulator (ATT never authorised),
        // so a host-supplied id is the only way to exercise this end to end.
        // Needs `advertisingId=true` at launch — the collection flag gates the setter.
        bind(R.id.btn_set_ad_id) {
            sdk {
                it.setAdvertisingId(SampleConfig.SAMPLE_ADVERTISING_ID, limitAdTracking = false)
                app.appendLog("setAdvertisingId ok")
            }
        }
        bind(R.id.btn_identify) { sdk { it.identify(userId.text.toString().ifBlank { null }, mapOf("plan" to "sample"), cb("identify")) } }
        bind(R.id.btn_track) { sdk { it.track("Sample Track", mapOf("count" to 1, "source" to "sample-app"), cb("track")) } }
        bind(R.id.btn_track_probe) { sdk { it.track("Pre Kill Probe", mapOf("probe" to true), cb("track")) } }
        bind(R.id.btn_screen) { sdk { it.screen("Home", "Sample", mapOf("tab" to "main"), cb("screen")) } }
        bind(R.id.btn_group) { sdk { it.group("acme", mapOf("plan" to "enterprise"), cb("group")) } }
        bind(R.id.btn_alias) { sdk { it.alias("aliased-${System.currentTimeMillis() % 1000}", null, cb("alias")) } }
        bind(R.id.btn_flush) { sdk { it.flush(); app.appendLog("flush requested") } }
        bind(R.id.btn_reset) { sdk { it.reset(); app.appendLog("reset") } }
        bind(R.id.btn_consent_state) { sdk { app.appendLog("consent state: ${it.getConsentState()}") } }
        render()
    }

    override fun onStart() {
        super.onStart()
        app.addLogListener(onLog)
        render()
    }

    override fun onStop() {
        app.removeLogListener(onLog)
        super.onStop()
    }

    private fun bind(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener { action() }
    }

    private fun sdk(block: (Analytics) -> Unit) {
        if (!Zeotap.isInitialized()) {
            app.appendLog("SDK not loaded")
            return
        }
        block(Zeotap.getInstance())
        render()
    }

    private fun cb(method: String) = Callback { error ->
        app.appendLog(if (error == null) "$method ok" else "$method dropped: ${error.message}")
    }

    private fun render() {
        val loaded = Zeotap.isInitialized()
        status.text = if (loaded) "loaded · anonymousId=${Zeotap.getInstance().anonymousId}" else "not loaded"
        val state = Zeotap.getConsentState()
        consentState.text = if (state.resolved) {
            "consent: ${state.source?.name?.lowercase()} track=${state.signals?.track} identify=${state.signals?.identify}"
        } else {
            "consent: unresolved"
        }
        logView.text = app.log.joinToString("\n")
    }
}
