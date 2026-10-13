package com.zeotap.sdk.internal.consent

import com.zeotap.sdk.ConsentInput
import com.zeotap.sdk.ConsentSignals
import com.zeotap.sdk.ConsentSource
import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.testing.FakeClock
import com.zeotap.sdk.internal.testing.InMemoryStorage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Mirrors `consent-persistence.test.ts`, asserting the exact persisted strings. */
class ConsentPersistenceTest {
    private val storage = InMemoryStorage()
    private val clock = FakeClock(NOW)

    private fun make(expiryDays: Int = 365) = ConsentPersistence(Keys("zeotap"), storage, expiryDays, clock)

    private fun resolution(
        signals: ConsentSignals = ConsentSignals(track = true, identify = true, cookieSync = false),
        input: ConsentInput? = ConsentInput.Parsed(analytics = true),
        tcfString: String? = null,
        gdprApplies: Boolean? = null,
        source: ConsentSource = ConsentSource.MANUAL,
        ts: Long = 1000L,
    ) = ResolvedConsent(signals, input, tcfString, gdprApplies, source, ts)

    @Test
    fun `save writes the exact main record and load returns it with source storage and the original ts`() {
        val persistence = make()
        persistence.save(resolution())
        assertEquals(
            """{"v":1,"signals":{"track":true,"identify":true,"cookieSync":false},"input":{"analytics":true},"source":"manual","ts":1000,"exp":1731536000000}""",
            storage.get(MAIN),
        )
        assertNull(storage.get(SIDE))

        val loaded = assertNotNull(persistence.load())
        assertEquals(ConsentSource.STORAGE, loaded.source)
        assertEquals(ConsentSignals(track = true, identify = true, cookieSync = false), loaded.signals)
        assertEquals(ConsentInput.Parsed(analytics = true), loaded.input)
        assertNull(loaded.tcfString)
        assertNull(loaded.gdprApplies)
        assertEquals(1000L, loaded.ts)
    }

    @Test
    fun `a TC string is kept out of the main record and stored under the side key`() {
        val persistence = make()
        persistence.save(resolution(input = ConsentInput.Tcf("TC-BIG"), tcfString = "TC-BIG", gdprApplies = true))
        assertEquals(
            """{"v":1,"signals":{"track":true,"identify":true,"cookieSync":false},"gdprApplies":true,"source":"manual","ts":1000,"exp":1731536000000}""",
            storage.get(MAIN),
        )
        assertEquals("""{"v":1,"tcfString":"TC-BIG","ts":1700000000000,"exp":1731536000000}""", storage.get(SIDE))

        val loaded = assertNotNull(persistence.load())
        assertEquals("TC-BIG", loaded.tcfString)
        assertEquals(true, loaded.gdprApplies)
        assertNull(loaded.input)
    }

    @Test
    fun `a cmp resolution with gdprApplies false has neither input nor side key`() {
        make().save(resolution(signals = ConsentSignals.uniform(true), input = null, gdprApplies = false, source = ConsentSource.CMP))
        assertEquals(
            """{"v":1,"signals":{"track":true,"identify":true,"cookieSync":true},"gdprApplies":false,"source":"cmp","ts":1000,"exp":1731536000000}""",
            storage.get(MAIN),
        )
        assertNull(storage.get(SIDE))
    }

    @Test
    fun `parsed input serialises in canonical order regardless of construction order`() {
        make().save(resolution(input = ConsentInput.Parsed(functional = true, analytics = false), signals = ConsentSignals.uniform(false)))
        assertEquals(
            """{"v":1,"signals":{"track":false,"identify":false,"cookieSync":false},"input":{"analytics":false,"functional":true},"source":"manual","ts":1000,"exp":1731536000000}""",
            storage.get(MAIN),
        )
    }

    @Test
    fun `custom expiry drives exp`() {
        make(expiryDays = 30).save(resolution())
        assertEquals(
            """{"v":1,"signals":{"track":true,"identify":true,"cookieSync":false},"input":{"analytics":true},"source":"manual","ts":1000,"exp":1702592000000}""",
            storage.get(MAIN),
        )
    }

    @Test
    fun `returns null and removes an expired record`() {
        val persistence = make(expiryDays = 1)
        persistence.save(resolution(tcfString = "TC"))
        clock.advance(2 * DAY)
        assertNull(persistence.load())
        assertFalse(storage.data.containsKey(MAIN))
        assertFalse(storage.data.containsKey(SIDE))
    }

    @Test
    fun `exp equal to now counts as expired`() {
        storage.set(MAIN, """{"v":1,"signals":{"track":true,"identify":true,"cookieSync":true},"source":"manual","ts":1,"exp":$NOW}""")
        assertNull(make().load())
        assertFalse(storage.data.containsKey(MAIN))
    }

    @Test
    fun `returns null and removes a corrupted or invalid record`() {
        for (raw in listOf(
            "{not json",
            "garbage",
            """{"v":2,"signals":{"track":true,"identify":true,"cookieSync":true},"source":"manual","ts":1,"exp":1800000000000}""",
            """{"v":1,"signals":{"track":true,"identify":true,"cookieSync":true},"source":"manual","ts":1}""",
            """{"v":1,"source":"manual","ts":1,"exp":1800000000000}""",
            """{"v":"1","signals":{"track":true,"identify":true,"cookieSync":true},"source":"manual","ts":1,"exp":1800000000000}""",
        )) {
            storage.set(MAIN, raw)
            storage.set(SIDE, """{"v":1,"tcfString":"TC","ts":1,"exp":1800000000000}""")
            assertNull(make().load(), raw)
            assertFalse(storage.data.containsKey(MAIN), raw)
            assertFalse(storage.data.containsKey(SIDE), raw)
        }
    }

    @Test
    fun `skips the write when the decision is unchanged`() {
        val persistence = make()
        persistence.save(resolution())
        assertEquals(1, storage.writes)

        clock.advance(60_000)
        persistence.save(resolution(ts = 2000)) // same decision, later ts
        assertEquals(1, storage.writes)
        assertEquals(
            """{"v":1,"signals":{"track":true,"identify":true,"cookieSync":false},"input":{"analytics":true},"source":"manual","ts":1000,"exp":1731536000000}""",
            storage.get(MAIN),
        )

        persistence.save(resolution(signals = ConsentSignals.uniform(false)))
        assertEquals(2, storage.writes)
    }

    @Test
    fun `a changed TC string or gdprApplies is a change`() {
        val persistence = make()
        val cmp = resolution(
            signals = ConsentSignals.uniform(true),
            input = null,
            tcfString = "CQ.a",
            gdprApplies = true,
            source = ConsentSource.CMP,
        )
        persistence.save(cmp)
        assertEquals(2, storage.writes)
        persistence.save(cmp.copy(ts = 5000))
        assertEquals(2, storage.writes)
        persistence.save(cmp.copy(tcfString = "CQ.b"))
        assertEquals(4, storage.writes)
        assertEquals("""{"v":1,"tcfString":"CQ.b","ts":1700000000000,"exp":1731536000000}""", storage.get(SIDE))
        persistence.save(cmp.copy(tcfString = "CQ.b", gdprApplies = null))
        assertEquals(6, storage.writes)
    }

    @Test
    fun `a later resolution without a TC string removes the side key`() {
        val persistence = make()
        persistence.save(resolution(input = null, tcfString = "CQ.a", gdprApplies = true, source = ConsentSource.CMP))
        assertNotNull(storage.get(SIDE))
        persistence.save(resolution())
        assertNull(storage.get(SIDE))
        assertEquals(3, storage.writes)
    }

    @Test
    fun `clear removes both keys`() {
        val persistence = make()
        persistence.save(resolution(tcfString = "TC"))
        persistence.clear()
        assertFalse(storage.data.containsKey(MAIN))
        assertFalse(storage.data.containsKey(SIDE))
        assertNull(persistence.load())
    }

    @Test
    fun `an expired or empty TC side key is dropped while the main record survives`() {
        val persistence = make()
        persistence.save(resolution(tcfString = "TC"))
        storage.set(SIDE, """{"v":1,"tcfString":"TC","ts":1,"exp":${NOW - 1}}""")
        val loaded = assertNotNull(persistence.load())
        assertNull(loaded.tcfString)
        assertFalse(storage.data.containsKey(SIDE))
        assertNotNull(storage.get(MAIN))

        storage.set(SIDE, """{"v":1,"tcfString":"","ts":1,"exp":1800000000000}""")
        assertNull(assertNotNull(persistence.load()).tcfString)
        assertFalse(storage.data.containsKey(SIDE))
    }

    @Test
    fun `a corrupt TC side key yields no TC string and is left in place`() {
        val persistence = make()
        persistence.save(resolution(tcfString = "TC"))
        storage.set(SIDE, "garbage")
        assertNull(assertNotNull(persistence.load()).tcfString)
        assertEquals("garbage", storage.get(SIDE))
    }

    @Test
    fun `a default-source resolution persists too`() {
        make().save(resolution(signals = ConsentSignals.uniform(false), input = null, source = ConsentSource.DEFAULT, ts = NOW))
        assertEquals(
            """{"v":1,"signals":{"track":false,"identify":false,"cookieSync":false},"source":"default","ts":1700000000000,"exp":1731536000000}""",
            storage.get(MAIN),
        )
        assertEquals(ConsentSource.STORAGE, make().load()?.source)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY = 86_400_000L
        const val MAIN = "zeotap_consent"
        const val SIDE = "zeotap_consent_tcf"
    }
}
