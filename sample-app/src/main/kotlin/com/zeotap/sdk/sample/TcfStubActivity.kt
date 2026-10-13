package com.zeotap.sdk.sample

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/**
 * Simulates a CMP: writes the IAB TCF-for-Apps keys the SDK observes into the
 * app's DEFAULT SharedPreferences (`<package>_preferences`). Presets cover the
 * three states the SDK's TCF timing model distinguishes.
 */
class TcfStubActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tcf_stub)
        val tcString = findViewById<EditText>(R.id.input_tc_string)
        val purposes = findViewById<EditText>(R.id.input_purposes)
        val vendors = findViewById<EditText>(R.id.input_vendors)
        val gdpr = findViewById<EditText>(R.id.input_gdpr_applies)

        findViewById<Button>(R.id.btn_preset_granted).setOnClickListener {
            tcString.setText("CQAbcSampleTCString")
            purposes.setText(purposeBits(1, 3, 4, 9))
            vendors.setText(vendorBits(1469))
            gdpr.setText("1")
        }
        findViewById<Button>(R.id.btn_preset_vendor_denied).setOnClickListener {
            tcString.setText("CQAbcSampleTCString")
            purposes.setText(purposeBits(1, 3, 4, 9))
            vendors.setText(vendorBits())
            gdpr.setText("1")
        }
        findViewById<Button>(R.id.btn_preset_gdpr_na).setOnClickListener {
            tcString.setText("")
            purposes.setText("")
            vendors.setText("")
            gdpr.setText("0")
        }
        findViewById<Button>(R.id.btn_tcf_write).setOnClickListener {
            write(tcString.text.toString(), purposes.text.toString(), vendors.text.toString(), gdpr.text.toString())
        }
        findViewById<Button>(R.id.btn_tcf_cmp_only).setOnClickListener {
            // "cmpuishown": a CMP is present but has not answered yet.
            defaults().edit().clear().putInt("IABTCF_CmpSdkID", 300).apply()
        }
        findViewById<Button>(R.id.btn_tcf_clear).setOnClickListener { defaults().edit().clear().apply() }
    }

    private fun defaults() = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

    private fun write(tcString: String, purposes: String, vendors: String, gdpr: String) {
        val editor = defaults().edit().putInt("IABTCF_CmpSdkID", 300)
        gdpr.toIntOrNull()?.let { editor.putInt("IABTCF_gdprApplies", it) } ?: editor.remove("IABTCF_gdprApplies")
        if (tcString.isNotEmpty()) editor.putString("IABTCF_TCString", tcString) else editor.remove("IABTCF_TCString")
        if (purposes.isNotEmpty()) editor.putString("IABTCF_PurposeConsents", purposes) else editor.remove("IABTCF_PurposeConsents")
        if (vendors.isNotEmpty()) editor.putString("IABTCF_VendorConsents", vendors) else editor.remove("IABTCF_VendorConsents")
        editor.apply()
    }

    companion object {
        fun purposeBits(vararg granted: Int): String = buildString { for (p in 1..24) append(if (p in granted) '1' else '0') }

        fun vendorBits(vararg granted: Int): String {
            val len = maxOf(1469, granted.maxOrNull() ?: 0)
            return buildString { for (v in 1..len) append(if (v in granted) '1' else '0') }
        }
    }
}
