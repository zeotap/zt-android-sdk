package com.zeotap.sdk.internal.identity

import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.testing.InMemoryStorage
import com.zeotap.sdk.internal.testing.SequenceIdGenerator
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Mirrors `sdk/js/src/identity.test.ts`. */
class IdentityStoreTest {
    private val storage = InMemoryStorage()
    private val ids = SequenceIdGenerator()

    private fun store(namespace: String = "test") = IdentityStore(Keys(namespace), storage, ids)

    @Test
    fun `mints an anonymous id and persists it on first construction`() {
        val identity = store()
        assertEquals("00000000-0000-4000-8000-000000000001", identity.anonymousId())
        assertEquals(identity.anonymousId(), storage.get("test_anonymous_id"))
        assertEquals(1, storage.writes)
    }

    @Test
    fun `loads an existing anonymous id without minting`() {
        storage.set("test_anonymous_id", "existing-anon-123")
        storage.resetCounters()
        assertEquals("existing-anon-123", store().anonymousId())
        assertEquals(0, storage.writes)
    }

    @Test
    fun `loads an existing user id`() {
        storage.set("test_user_id", "user-456")
        assertEquals("user-456", store().userId())
    }

    @Test
    fun `user id is null initially`() {
        assertNull(store().userId())
    }

    @Test
    fun `setAnonymousId overrides and persists`() {
        val identity = store()
        identity.setAnonymousId("custom-anon-id")
        assertEquals("custom-anon-id", identity.anonymousId())
        assertEquals("custom-anon-id", storage.get("test_anonymous_id"))
    }

    @Test
    fun `setUserId sets and persists`() {
        val identity = store()
        identity.setUserId("user-789")
        assertEquals("user-789", identity.userId())
        assertEquals("user-789", storage.get("test_user_id"))
    }

    @Test
    fun `reset clears the user id and mints a different anonymous id`() {
        val identity = store()
        identity.setUserId("user-to-clear")
        val previous = identity.anonymousId()

        identity.reset()

        assertNull(identity.userId())
        assertNull(storage.get("test_user_id"))
        assertNotEquals(previous, identity.anonymousId())
        assertEquals("00000000-0000-4000-8000-000000000002", identity.anonymousId())
        assertEquals(identity.anonymousId(), storage.get("test_anonymous_id"))
    }

    @Test
    fun `namespaces isolate keys`() {
        val a = store("brand_a")
        val b = store("brand_b")
        a.setUserId("user-a")
        b.setUserId("user-b")
        assertEquals("user-a", a.userId())
        assertEquals("user-b", b.userId())
        assertEquals("user-a", storage.get("brand_a_user_id"))
        assertEquals("user-b", storage.get("brand_b_user_id"))
        assertNotEquals(a.anonymousId(), b.anonymousId())
    }
}
