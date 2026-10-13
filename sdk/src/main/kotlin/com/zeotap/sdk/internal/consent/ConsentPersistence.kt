package com.zeotap.sdk.internal.consent

import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentSignals
import com.zeotap.sdk.ConsentSource
import com.zeotap.sdk.Storage
import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.util.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persisted consent — port of `sdk/js/src/consent-persistence.ts`. Two records in [Storage]:
 * `{ns}_consent` = `{"v":1,"signals","input"?,"gdprApplies"?,"source","ts","exp"}` and the side key
 * `{ns}_consent_tcf` = `{"v":1,"tcfString","ts","exp"}` (the TC string never rides in the main record).
 * Hydration yields source STORAGE with the record's original `ts`; saves skip when unchanged.
 */
internal class ConsentPersistence(
    private val keys: Keys,
    private val storage: Storage,
    private val expiryDays: Int,
    private val clock: Clock,
) {
    private data class StoredRecord(
        val signals: ConsentSignals,
        val input: ConsentInput?,
        val gdprApplies: Boolean?,
        val ts: Long,
        val exp: Long,
    )

    /** The persisted resolution, or null when absent / expired / corrupt (both keys are then removed). */
    fun load(): ResolvedConsent? {
        val record = readRecord() ?: return null
        return ResolvedConsent(
            signals = record.signals,
            input = record.input,
            tcfString = readTcfString(),
            gdprApplies = record.gdprApplies,
            source = ConsentSource.STORAGE,
            ts = record.ts,
        )
    }

    /** Persist; skipped when signals, input, gdprApplies AND the stored TC string are all unchanged. */
    fun save(resolution: ResolvedConsent) {
        val now = clock.nowMs()
        // A manual {tcfString} input is stored via the side key, not inline.
        val input = resolution.input?.takeUnless { it is ConsentInput.Tcf }
        val existing = readRecord()
        val unchanged = existing != null &&
            sameDecision(existing, resolution, input) &&
            readTcfString() == resolution.tcfString
        if (unchanged) return
        storage.set(keys.consent, mainRecord(resolution, input, now + ttlMs()).toString())
        writeTcfString(resolution.tcfString, now)
    }

    /** Remove both keys (reset / consent withdrawal). */
    fun clear() {
        storage.remove(keys.consent)
        storage.remove(keys.consentTcf)
    }

    // -- Private helpers -------------------------------------------------------------------------

    private fun ttlMs(): Long = expiryDays * DAY_MS

    private fun sameDecision(existing: StoredRecord, resolution: ResolvedConsent, input: ConsentInput?): Boolean =
        existing.signals == resolution.signals &&
            existing.input == input &&
            existing.gdprApplies == resolution.gdprApplies

    private fun mainRecord(resolution: ResolvedConsent, input: ConsentInput?, exp: Long): JsonObject = buildJsonObject {
        put("v", 1)
        put("signals", ConsentState.signalsToJson(resolution.signals))
        if (input != null) put("input", ConsentState.toJson(input))
        resolution.gdprApplies?.let { put("gdprApplies", it) }
        put("source", resolution.source.wire)
        put("ts", resolution.ts)
        put("exp", exp)
    }

    private fun readRecord(): StoredRecord? {
        val raw = storage.get(keys.consent)
        if (raw.isNullOrEmpty()) return null
        val record = runCatching { parseRecord(raw) }.getOrNull()
        val valid = record != null && record.exp > clock.nowMs()
        if (!valid) clear()
        return if (valid) record else null
    }

    private fun parseRecord(raw: String): StoredRecord {
        val obj = parseJsonStrict(raw) as? JsonObject
        requireNotNull(obj) { "consent record is not an object" }
        val v = obj.numberOrNull("v")
        val exp = obj.numberOrNull("exp")
        val signals = obj["signals"] as? JsonObject
        require(v == 1L && exp != null && signals != null) { "invalid consent record" }
        return StoredRecord(
            signals = ConsentState.signalsFromJson(signals),
            input = (obj["input"] as? JsonObject)?.let(ConsentState::fromJson),
            gdprApplies = (obj["gdprApplies"] as? JsonPrimitive)?.booleanOrNull,
            ts = obj.numberOrNull("ts") ?: 0L,
            exp = exp,
        )
    }

    /**
     * The side record's TC string. Invalid (wrong version, expired, empty) → key removed, null.
     * Unparseable → null but the key is LEFT in place (the web read is try/caught without cleanup).
     */
    private fun readTcfString(): String? {
        val raw = storage.get(keys.consentTcf)
        if (raw.isNullOrEmpty()) return null
        val element = runCatching { parseJsonStrict(raw) }.getOrNull() ?: return null
        val record = element as? JsonObject
        val exp = record?.numberOrNull("exp")
        val tcfString = record?.stringOrNull("tcfString")
        val expired = exp != null && exp <= clock.nowMs()
        val valid = record?.numberOrNull("v") == 1L && !expired && !tcfString.isNullOrEmpty()
        if (!valid) storage.remove(keys.consentTcf)
        return if (valid) tcfString else null
    }

    private fun writeTcfString(tcfString: String?, now: Long) {
        if (tcfString.isNullOrEmpty()) {
            storage.remove(keys.consentTcf)
            return
        }
        val record = buildJsonObject {
            put("v", 1)
            put("tcfString", tcfString)
            put("ts", now)
            put("exp", now + ttlMs())
        }
        storage.set(keys.consentTcf, record.toString())
    }

    private fun JsonObject.numberOrNull(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        val content = primitive.content
        return content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive is JsonNull) null else primitive.content
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

        /**
         * `JSON.parse` semantics: kotlinx accepts a bare top-level word (`garbage`) as an unquoted
         * literal, where JSON.parse throws. The two differ observably (throw = leave the record in
         * place; parse = validate and remove it), so reject anything that is not a JSON literal.
         */
        fun parseJsonStrict(raw: String): JsonElement {
            val element = Json.parseToJsonElement(raw)
            if (element is JsonPrimitive && element !is JsonNull && !element.isString) {
                val content = element.content
                val literal = content == "true" || content == "false" || JSON_NUMBER.matches(content)
                require(literal) { "not valid JSON" }
            }
            return element
        }
    }
}
