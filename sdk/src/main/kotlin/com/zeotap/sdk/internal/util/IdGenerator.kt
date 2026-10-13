package com.zeotap.sdk.internal.util

import java.security.SecureRandom

/** RFC-4122 v4 UUIDs, lowercase hyphenated — same shape as the web SDK's `uuid()`. */
internal interface IdGenerator {
    fun uuid(): String
}

internal object SecureRandomIdGenerator : IdGenerator {
    private val random = SecureRandom()

    override fun uuid(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte() // version 4
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // variant 1
        val hex = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 0x10) hex.append('0')
            hex.append(Integer.toHexString(v))
        }
        return buildString(36) {
            append(hex, 0, 8); append('-')
            append(hex, 8, 12); append('-')
            append(hex, 12, 16); append('-')
            append(hex, 16, 20); append('-')
            append(hex, 20, 32)
        }
    }
}
