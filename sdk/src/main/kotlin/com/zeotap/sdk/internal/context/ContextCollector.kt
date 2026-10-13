package com.zeotap.sdk.internal.context

import kotlinx.serialization.json.JsonElement

/**
 * Supplies the mobile context blocks the web SDK never sends, in the order
 * they appear in `context` (after `library`): app, device, os, network, screen,
 * locale, timezone, userAgent. Static blocks are computed once; `network` is
 * refreshed per event. Privacy defaults: no ANDROID_ID, no Wi-Fi IP, no
 * device name; advertising id only when opted in.
 */
internal interface ContextCollector {
    /** Ordered context entries to append after `library`. */
    fun collect(): List<Pair<String, JsonElement>>
}
