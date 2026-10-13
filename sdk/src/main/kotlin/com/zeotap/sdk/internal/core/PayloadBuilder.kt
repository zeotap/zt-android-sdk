package com.zeotap.sdk.internal.core

import com.zeotap.sdk.IntegrationMeta
import com.zeotap.sdk.internal.context.ContextCollector
import com.zeotap.sdk.internal.util.Iso8601
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An event under construction. `fields` holds the top-level keys in wire
 * order with a placeholder at `context`; `context` is filled in until the
 * enqueue step materialises it (identities and a missing consent are attached
 * at ENQUEUE time, as in the browser SDK).
 */
internal class EventDraft(
    val type: String,
    val messageId: String,
    val fields: LinkedHashMap<String, JsonElement>,
    val context: LinkedHashMap<String, JsonElement>,
) {
    /** Final wire object with `context` in its original position. */
    fun materialise(): JsonObject {
        fields["context"] = JsonObject(context)
        return JsonObject(fields)
    }
}

/**
 * Builds the base payload exactly like `client.ts` `buildPayload`/`buildContext`:
 * `messageId, type, anonymousId, context, timestamp, sentAt, [userId]`, with
 * `timestamp == sentAt == creation time`. Context: `library{name, version,
 * integration?}` followed by the mobile blocks from [ContextCollector], then
 * `consent`/`identities` appended later by the client.
 */
internal class PayloadBuilder(
    private val libraryName: String,
    private val libraryVersion: String,
    private val integration: IntegrationMeta?,
    private val contextCollector: ContextCollector,
) {
    fun base(type: String, messageId: String, anonymousId: String, userId: String?, nowMs: Long): EventDraft {
        val now = Iso8601.format(nowMs)
        val fields = LinkedHashMap<String, JsonElement>()
        fields["messageId"] = JsonPrimitive(messageId)
        fields["type"] = JsonPrimitive(type)
        fields["anonymousId"] = JsonPrimitive(anonymousId)
        fields["context"] = JsonNull // placeholder, replaced by materialise()
        fields["timestamp"] = JsonPrimitive(now)
        fields["sentAt"] = JsonPrimitive(now)
        if (userId != null) fields["userId"] = JsonPrimitive(userId)

        val context = LinkedHashMap<String, JsonElement>()
        val library = LinkedHashMap<String, JsonElement>()
        library["name"] = JsonPrimitive(libraryName)
        library["version"] = JsonPrimitive(libraryVersion)
        if (integration != null) {
            library["integration"] = JsonObject(
                linkedMapOf("name" to JsonPrimitive(integration.name), "version" to JsonPrimitive(integration.version)),
            )
        }
        context["library"] = JsonObject(library)
        for ((key, value) in contextCollector.collect()) context[key] = value
        return EventDraft(type, messageId, fields, context)
    }
}
