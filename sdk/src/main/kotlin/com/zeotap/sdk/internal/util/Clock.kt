package com.zeotap.sdk.internal.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Wall clock in epoch milliseconds. Injectable so tests pin `ts`/`exp`/timestamps. */
internal interface Clock {
    fun nowMs(): Long
}

internal object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * ISO-8601 UTC with exactly three fractional digits — byte-identical to
 * JavaScript's `Date.prototype.toISOString()` (`2026-01-15T10:20:30.123Z`).
 * `Locale.US` + explicit UTC so a device on a non-Gregorian locale/zone cannot
 * change the output.
 */
internal object Iso8601 {
    // ThreadLocal.withInitial is API 26; minSdk is 23.
    private val formatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    fun format(epochMs: Long): String = formatter.get()!!.format(Date(epochMs))
}
