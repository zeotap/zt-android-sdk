package com.zeotap.sdk.sample

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** Edits [SampleConfig]. Takes effect on the next Load (the SDK is loaded once per process). */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val cfg = SampleConfig.get(this)
        val apiHost = findViewById<EditText>(R.id.input_api_host).apply { setText(cfg.apiHost) }
        val writeKey = findViewById<EditText>(R.id.input_write_key).apply { setText(cfg.writeKey) }
        val flushAt = findViewById<EditText>(R.id.input_flush_at).apply { setText(cfg.flushAt.toString()) }
        val flushInterval = findViewById<EditText>(R.id.input_flush_interval).apply { setText(cfg.flushIntervalMs.toString()) }
        val cmpTimeout = findViewById<EditText>(R.id.input_cmp_timeout).apply { setText(cfg.cmpTimeoutMs.toString()) }
        val require = findViewById<CheckBox>(R.id.chk_consent_require).apply { isChecked = cfg.consentRequire }
        val denied = findViewById<CheckBox>(R.id.chk_consent_denied).apply { isChecked = cfg.consentDefault == "denied" }
        val cmpAuto = findViewById<CheckBox>(R.id.chk_cmp_auto).apply { isChecked = cfg.cmp == "auto" }
        val lifecycle = findViewById<CheckBox>(R.id.chk_lifecycle).apply { isChecked = cfg.lifecycleEvents }
        val adId = findViewById<CheckBox>(R.id.chk_ad_id).apply { isChecked = cfg.advertisingId }
        val autoLoad = findViewById<CheckBox>(R.id.chk_auto_load).apply { isChecked = cfg.autoLoad }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            cfg.apiHost = apiHost.text.toString().trim()
            cfg.writeKey = writeKey.text.toString().trim()
            cfg.flushAt = flushAt.text.toString().toIntOrNull() ?: 10
            cfg.flushIntervalMs = flushInterval.text.toString().toLongOrNull() ?: 5_000L
            cfg.cmpTimeoutMs = cmpTimeout.text.toString().toLongOrNull() ?: 10_000L
            cfg.consentRequire = require.isChecked
            cfg.consentDefault = if (denied.isChecked) "denied" else "granted"
            cfg.cmp = if (cmpAuto.isChecked) "auto" else "none"
            cfg.lifecycleEvents = lifecycle.isChecked
            cfg.advertisingId = adId.isChecked
            cfg.autoLoad = autoLoad.isChecked
            finish()
        }
    }
}
