package com.zeotap.sdk.internal.testing

import com.zeotap.sdk.internal.transport.HttpClient
import com.zeotap.sdk.internal.transport.HttpResponse
import java.io.IOException

/**
 * Scripted [HttpClient]: each `post()` consumes the next entry of [responses]
 * and records the request. Entries may be an `Int` status (body `{}`), an
 * [HttpResponse], a `Pair<Int, String>` (status, body) or an [IOException]
 * to throw. Running out of script fails the test loudly.
 */
internal class ScriptedHttpClient(private val responses: MutableList<Any>) : HttpClient {
    data class Request(val url: String, val headers: Map<String, String>, val body: String)

    val requests = ArrayList<Request>()

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        requests.add(Request(url, LinkedHashMap(headers), body.toString(Charsets.UTF_8)))
        check(responses.isNotEmpty()) { "ScriptedHttpClient: no scripted response for request #${requests.size}" }
        return when (val next = responses.removeAt(0)) {
            is IOException -> throw next
            is Int -> HttpResponse(next, "{}")
            is HttpResponse -> next
            is Pair<*, *> -> HttpResponse(next.first as Int, next.second as String)
            else -> error("ScriptedHttpClient: unsupported scripted response $next")
        }
    }
}

/** Records the delays the transport asked for and returns immediately. */
internal class RecordingSleep {
    val delays = ArrayList<Long>()

    val sleep: suspend (Long) -> Unit = { delays.add(it) }
}
