package com.zeotap.sdk.internal.queue

/** UTF-8 size of a payload string without allocating the encoded bytes — used for the reserve byte budget. */
internal object PayloadBytes {
    fun utf8Length(payload: String): Int {
        var bytes = 0
        var i = 0
        val n = payload.length
        while (i < n) {
            val c = payload[i]
            when {
                c.code < 0x80 -> bytes += 1
                c.code < 0x800 -> bytes += 2
                Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(payload[i + 1]) -> {
                    bytes += 4
                    i++
                }
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }
}
