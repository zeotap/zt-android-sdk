package com.zeotap.sdk.internal.identity

import com.zeotap.sdk.Logger
import com.zeotap.sdk.Storage
import com.zeotap.sdk.internal.storage.Keys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PII user identities — port of `sdk/js/src/user-identities.ts`. Raw email/loginid/cellno input is
 * normalised and hashed synchronously; pre-hashed legacy flat keys are regrouped; fpuid and custom
 * keys pass through raw. The map is persisted verbatim under `{ns}_user_identities` (insertion order
 * kept) and attaches to every event as `context.identities`. Identity values are never logged.
 */
internal class UserIdentityManager(
    private val keys: Keys,
    private val storage: Storage,
    private val logger: Logger,
) {
    private var identities: LinkedHashMap<String, JsonElement> = read()

    /** Current identities, or null when empty. */
    fun get(): JsonObject? = if (identities.isEmpty()) null else JsonObject(LinkedHashMap(identities))

    /** Merge `{...existing, ...resolved}` — existing keys keep their position — and persist. */
    fun setIdentities(input: Map<String, String?>, hashed: Boolean) {
        val resolved = if (hashed) regroupHashed(input) else hashRaw(input)
        identities = LinkedHashMap(identities).apply { putAll(resolved) }
        persist()
    }

    /** Remove specific identity keys, or everything when `keys` is null or empty. */
    fun unset(keys: List<String>?) {
        if (keys.isNullOrEmpty()) {
            identities = LinkedHashMap()
        } else {
            for (key in keys) identities.remove(key)
        }
        persist()
    }

    /** Clear everything (reset / logout). */
    fun clear() {
        unset(null)
    }

    // -- Raw path --------------------------------------------------------------------------------

    private fun hashRaw(input: Map<String, String?>): LinkedHashMap<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in input) {
            if (value.isNullOrEmpty()) continue
            when {
                key in CASE_KEYS -> out[key] = toJson(Hasher.caseVariants(value))
                key == KEY_CELLNO -> addCellno(out, value, input[KEY_CELLNO_CC])
                key == KEY_CELLNO_CC -> Unit // consumed alongside cellno, never emitted
                else -> out[key] = JsonPrimitive(value) // fpuid and custom keys pass through raw
            }
        }
        return out
    }

    private fun addCellno(out: LinkedHashMap<String, JsonElement>, raw: String, explicitCc: String?) {
        val (number, cc) = Hasher.cleanCellno(raw, explicitCc)
        if (number.isEmpty()) return
        out[KEY_CELLNO] = toJson(Hasher.digits(number))
        if (cc != null) out[KEY_CELLNO_E164] = toJson(Hasher.digits(cc + number))
    }

    // -- Pre-hashed path -------------------------------------------------------------------------

    private fun regroupHashed(input: Map<String, String?>): LinkedHashMap<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in input) {
            if (value.isNullOrEmpty()) continue
            val caseMatch = HASHED_CASE_RE.matchEntire(key)
            val digitMatch = HASHED_DIGIT_RE.matchEntire(key)
            when {
                caseMatch != null -> {
                    val (base, algo, casing) = caseMatch.destructured
                    addToGroup(out, base, "${algo}_$casing", value)
                }
                digitMatch != null -> {
                    val (base, algo) = digitMatch.destructured
                    addToGroup(out, base, algo, value)
                }
                else -> out[key] = JsonPrimitive(value) // fpuid / custom ids stay raw even in hashed mode
            }
        }
        return out
    }

    private fun addToGroup(out: LinkedHashMap<String, JsonElement>, base: String, variant: String, value: String) {
        val group = LinkedHashMap<String, JsonElement>((out[base] as? JsonObject) ?: emptyMap())
        group[variant] = JsonPrimitive(value)
        out[base] = JsonObject(group)
    }

    // -- Persistence -----------------------------------------------------------------------------

    private fun read(): LinkedHashMap<String, JsonElement> {
        val raw = storage.get(keys.userIdentities)
        if (raw.isNullOrEmpty()) return LinkedHashMap()
        return try {
            val obj = Json.parseToJsonElement(raw) as? JsonObject
            if (obj == null) {
                logger.warn("[SDK] Ignoring malformed user identities record")
                LinkedHashMap()
            } else {
                LinkedHashMap(obj)
            }
        } catch (e: Exception) {
            logger.warn("[SDK] Ignoring corrupt user identities record")
            LinkedHashMap()
        }
    }

    private fun persist() {
        try {
            if (identities.isEmpty()) {
                storage.remove(keys.userIdentities)
            } else {
                storage.set(keys.userIdentities, JsonObject(identities).toString())
            }
        } catch (e: Exception) {
            // The in-memory copy remains authoritative for the session (web parity).
            logger.error("[SDK] Failed to persist user identities", e)
        }
    }

    private fun toJson(map: Map<String, String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(map.size)
        for ((k, v) in map) out[k] = JsonPrimitive(v)
        return JsonObject(out)
    }

    private companion object {
        const val KEY_CELLNO = "cellno"
        const val KEY_CELLNO_CC = "cellno_cc"
        const val KEY_CELLNO_E164 = "cellno_e164"

        /** Identity keys the SDK hashes case-sensitively. */
        val CASE_KEYS = setOf("email", "loginid")

        /** Flat pre-hashed key patterns (legacy convention). */
        val HASHED_CASE_RE = Regex("^(email|loginid)_(sha256|sha1|md5)_(lowercase|uppercase)$")
        val HASHED_DIGIT_RE =
            Regex("^(cellno|cellno_e164|cellno_with_country_code|cellno_without_country_code)_(sha256|sha1|md5)$")
    }
}
