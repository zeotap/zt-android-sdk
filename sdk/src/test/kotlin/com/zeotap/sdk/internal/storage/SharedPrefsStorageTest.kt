package com.zeotap.sdk.internal.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class SharedPrefsStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = SharedPrefsStorage(context, "zeotap_sdk")

    @Test
    fun `get returns null for a missing key`() {
        assertNull(storage.get("zeotap_anonymous_id"))
    }

    @Test
    fun `set then get round-trips and remove deletes`() {
        storage.set("zeotap_anonymous_id", "abc")
        assertEquals("abc", storage.get("zeotap_anonymous_id"))
        storage.set("zeotap_anonymous_id", "def")
        assertEquals("def", storage.get("zeotap_anonymous_id"))
        storage.remove("zeotap_anonymous_id")
        assertNull(storage.get("zeotap_anonymous_id"))
    }

    @Test
    fun `values are visible to a new instance over the same file`() {
        storage.set("zeotap_consent", """{"v":1}""")
        assertEquals("""{"v":1}""", SharedPrefsStorage(context, "zeotap_sdk").get("zeotap_consent"))
        assertEquals("""{"v":1}""", context.getSharedPreferences("zeotap_sdk", Context.MODE_PRIVATE).getString("zeotap_consent", null))
    }

    @Test
    fun `different files are isolated`() {
        storage.set("k", "a")
        val other = SharedPrefsStorage(context, "brand_b_sdk")
        assertNull(other.get("k"))
        other.set("k", "b")
        assertEquals("a", storage.get("k"))
        assertEquals("b", other.get("k"))
    }

    @Test
    fun `a non-string value under the key reads as null instead of throwing`() {
        context.getSharedPreferences("zeotap_sdk", Context.MODE_PRIVATE).edit().putInt("k", 7).commit()
        assertNull(storage.get("k"))
    }

    @Test
    fun `removing a missing key is a no-op`() {
        storage.remove("nope")
        assertNull(storage.get("nope"))
    }
}
