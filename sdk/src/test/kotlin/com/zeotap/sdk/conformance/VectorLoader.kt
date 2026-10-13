package com.zeotap.sdk.conformance

import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentOptions
import com.zeotap.sdk.ConsentSignals
import com.zeotap.sdk.ConsentSource
import com.zeotap.sdk.internal.consent.ConsentState
import com.zeotap.sdk.internal.consent.ResolvedConsent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Loads `sdk/conformance/vectors/<family>.vectors.json` from the test classpath (copied there by the
 * `copyConformanceVectors` Gradle task) and exposes the cases this platform must satisfy.
 */
internal object VectorLoader {
    private const val PLATFORM = "android"

    fun load(family: String): JsonObject {
        val path = "/conformance/$family.vectors.json"
        val stream = VectorLoader::class.java.getResourceAsStream(path)
            ?: error("Missing conformance vectors at $path — run the copyConformanceVectors Gradle task")
        return stream.use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)) }.jsonObject
    }

    /** Cases without a `platforms` list, or whose list names this platform. */
    fun cases(family: String): List<JsonObject> = load(family).getValue("cases").jsonArray
        .map { it.jsonObject }
        .filter { case ->
            val platforms = case["platforms"] as? JsonArray
            platforms == null || platforms.any { it.jsonPrimitive.content == PLATFORM }
        }

    /** JUnit `Parameterized` rows `(name, case)` so test names read `family/id`. */
    fun parameters(family: String): Collection<Array<Any>> =
        cases(family).map { arrayOf<Any>("$family/${it.string("id")}", it) }
}

// -- JSON accessors (JsonNull and absent both read as null) ----------------------------------------

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonObject.stringOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive is JsonNull) null else primitive.content
}

internal fun JsonObject.longOrNull(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive is JsonNull) null else primitive.content.toLongOrNull()
}

internal fun JsonObject.boolOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

/** `{"k": "v", ...}` → ordered `Map<String, String?>` (JsonNull → null). */
internal fun JsonObject.toStringMap(): Map<String, String?> {
    val out = LinkedHashMap<String, String?>()
    for ((k, v) in this) out[k] = (v as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    return out
}

/** `{"1469": true, ...}` → `Map<Int, Boolean>`. */
internal fun JsonObject.toIntBoolMap(): Map<Int, Boolean> {
    val out = LinkedHashMap<Int, Boolean>()
    for ((k, v) in this) out[k.toInt()] = v.jsonPrimitive.booleanOrNull ?: false
    return out
}

// -- Consent shapes ------------------------------------------------------------------------------

internal fun JsonObject.toSignals(): ConsentSignals = ConsentState.signalsFromJson(this)

internal fun JsonObject.toConsentInput(): ConsentInput = requireNotNull(ConsentState.fromJson(this))

/** An `offer` step / persisted `resolution`: `{signals, input?, tcfString?, gdprApplies?, source, ts}`. */
internal fun JsonObject.toResolvedConsent(): ResolvedConsent = ResolvedConsent(
    signals = requireNotNull(objOrNull("signals")).toSignals(),
    input = objOrNull("input")?.toConsentInput(),
    tcfString = stringOrNull("tcfString"),
    gdprApplies = boolOrNull("gdprApplies"),
    source = requireNotNull(ConsentSource.fromWire(stringOrNull("source"))) { "unknown source ${stringOrNull("source")}" },
    ts = requireNotNull(longOrNull("ts")),
)

/** The vector shape of a resolution: `{signals, input?, gdprApplies?, tcfString?, source, ts}` (compared structurally). */
internal fun ResolvedConsent.toVectorJson(): JsonObject = buildJsonObject {
    put("signals", ConsentState.signalsToJson(signals))
    input?.let { put("input", ConsentState.toJson(it)) }
    gdprApplies?.let { put("gdprApplies", it) }
    tcfString?.let { put("tcfString", it) }
    put("source", source.wire)
    put("ts", ts)
}

/** `tcf` vector `options` → [ConsentOptions]. */
internal fun JsonObject.toConsentOptions(): ConsentOptions {
    val builder = ConsentOptions.Builder()
    arrayOrNull("purposesForTracking")?.let { builder.purposesForTracking(it.map { p -> p.jsonPrimitive.int }) }
    arrayOrNull("purposesForIdentifying")?.let { builder.purposesForIdentifying(it.map { p -> p.jsonPrimitive.int }) }
    arrayOrNull("purposesForCookieSync")?.let { builder.purposesForCookieSync(it.map { p -> p.jsonPrimitive.int }) }
    boolOrNull("checkVendorConsent")?.let { builder.checkVendorConsent(it) }
    return builder.build()
}
