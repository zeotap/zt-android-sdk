package com.zeotap.sdk.internal.identity

import com.zeotap.sdk.Storage
import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.util.IdGenerator

/**
 * Anonymous-id / user-id persistence — port of `sdk/js/src/identity.ts` over the SDK [Storage].
 * The anonymous id is minted and persisted on first construction when absent.
 */
internal class IdentityStore(
    private val keys: Keys,
    private val storage: Storage,
    private val ids: IdGenerator,
) {
    private var anonymousId: String = loadAnonymousId()
    private var userId: String? = storage.get(keys.userId)?.takeIf { it.isNotEmpty() }

    fun anonymousId(): String = anonymousId

    /** Override the anonymous id (hybrid WebView / bridge use) and persist it. */
    fun setAnonymousId(id: String) {
        anonymousId = id
        storage.set(keys.anonymousId, id)
    }

    fun userId(): String? = userId

    fun setUserId(id: String) {
        userId = id
        storage.set(keys.userId, id)
    }

    /** Logout: drop the user id and mint a fresh anonymous id. */
    fun reset() {
        userId = null
        storage.remove(keys.userId)
        anonymousId = ids.uuid()
        storage.set(keys.anonymousId, anonymousId)
    }

    private fun loadAnonymousId(): String {
        val stored = storage.get(keys.anonymousId)
        if (!stored.isNullOrEmpty()) return stored
        val id = ids.uuid()
        storage.set(keys.anonymousId, id)
        return id
    }
}
