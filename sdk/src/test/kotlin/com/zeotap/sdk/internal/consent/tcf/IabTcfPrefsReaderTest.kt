package com.zeotap.sdk.internal.consent.tcf

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class IabTcfPrefsReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    private val reader = IabTcfPrefsReader(context)

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `reads absent keys as an empty record with no CMP present`() {
        assertEquals(TcData.EMPTY, reader.read())
        assertFalse(reader.read().cmpPresent)
    }

    @Test
    fun `reads the IABTCF keys from the default preferences file`() {
        prefs.edit()
            .putInt("IABTCF_CmpSdkID", 42)
            .putInt("IABTCF_gdprApplies", 1)
            .putString("IABTCF_TCString", "CQ.tc")
            .putString("IABTCF_PurposeConsents", "1011")
            .putString("IABTCF_VendorConsents", "0001")
            .commit()

        val expected = TcData(cmpSdkId = 42, gdprApplies = true, tcString = "CQ.tc", purposeConsents = "1011", vendorConsents = "0001")
        assertEquals(expected, reader.read())
        assertTrue(reader.read().cmpPresent)
    }

    @Test
    fun `gdprApplies 0 reads as false and a String value is tolerated`() {
        prefs.edit().putInt("IABTCF_gdprApplies", 0).commit()
        assertEquals(false, reader.read().gdprApplies)

        prefs.edit().putString("IABTCF_gdprApplies", "1").commit()
        assertEquals(true, reader.read().gdprApplies)

        prefs.edit().putString("IABTCF_CmpSdkID", "not a number").commit()
        assertEquals(null, reader.read().cmpSdkId)
    }

    @Test
    fun `only the CmpSdkID marks the CMP present`() {
        prefs.edit().putInt("IABTCF_CmpSdkID", 42).commit()
        assertTrue(reader.read().cmpPresent)
    }

    @Test
    fun `listener fires on IABTCF changes and stops after close`() {
        var fired = 0
        val handle = reader.observe { fired++ }

        prefs.edit().putString("IABTCF_TCString", "CQ.a").commit()
        ShadowLooper.idleMainLooper()
        assertEquals(1, fired)

        prefs.edit().putString("unrelated_key", "x").commit()
        ShadowLooper.idleMainLooper()
        assertEquals(1, fired)

        handle.close()
        prefs.edit().putString("IABTCF_TCString", "CQ.b").commit()
        ShadowLooper.idleMainLooper()
        assertEquals(1, fired)
    }

    @Test
    fun `multiple observers are independent`() {
        var a = 0
        var b = 0
        val handleA = reader.observe { a++ }
        reader.observe { b++ }
        prefs.edit().putInt("IABTCF_gdprApplies", 1).commit()
        ShadowLooper.idleMainLooper()
        handleA.close()
        prefs.edit().putInt("IABTCF_gdprApplies", 0).commit()
        ShadowLooper.idleMainLooper()
        assertEquals(1, a)
        assertEquals(2, b)
    }
}
