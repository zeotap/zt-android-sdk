package com.zeotap.sdk.internal.storage

import android.content.Context
import android.content.SharedPreferences
import com.zeotap.sdk.Storage

/** Default [Storage]: a private SharedPreferences file (`{namespace}_sdk`), written with `apply()`. */
internal class SharedPrefsStorage(context: Context, fileName: String) : Storage {
    private val prefs: SharedPreferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun get(key: String): String? = try {
        prefs.getString(key, null)
    } catch (e: ClassCastException) {
        null // a foreign non-string value under our key reads as absent
    }

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
