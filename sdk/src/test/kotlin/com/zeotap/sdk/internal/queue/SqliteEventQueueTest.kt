package com.zeotap.sdk.internal.queue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class SqliteEventQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "test_${System.nanoTime()}_sdk_events.db"
    private val open = ArrayList<SqliteEventQueue>()

    private fun queue(maxSize: Int = 500): SqliteEventQueue =
        SqliteEventQueue(context, dbName, maxSize).also { open.add(it) }

    private fun EventQueue.drain(batchId: String = "b"): List<String> {
        val rows = reserve(batchId, Long.MAX_VALUE)
        ack(batchId)
        return rows.map { it.messageId }
    }

    @After
    fun tearDown() {
        open.forEach { it.close() }
        context.deleteDatabase(dbName)
    }

    @Test
    fun `enqueue increases size and keeps FIFO order`() {
        val q = queue()
        assertThat(q.size()).isEqualTo(0)
        q.enqueue("first", "{\"messageId\":\"first\"}")
        q.enqueue("second", "{\"messageId\":\"second\"}")
        q.enqueue("third", "{\"messageId\":\"third\"}")
        assertThat(q.size()).isEqualTo(3)
        val rows = q.reserve("x", Long.MAX_VALUE)
        assertThat(rows.map { it.messageId }).containsExactly("first", "second", "third").inOrder()
        assertThat(rows.map { it.payload }).containsExactly(
            "{\"messageId\":\"first\"}", "{\"messageId\":\"second\"}", "{\"messageId\":\"third\"}",
        ).inOrder()
        assertThat(rows[0].id).isLessThan(rows[1].id)
    }

    @Test
    fun `ack deletes the batch and clear empties everything`() {
        val q = queue()
        q.enqueue("a", "1")
        q.enqueue("b", "2")
        q.reserve("x", Long.MAX_VALUE)
        q.ack("x")
        assertThat(q.size()).isEqualTo(0)

        q.enqueue("c", "3")
        q.clear()
        assertThat(q.size()).isEqualTo(0)
        assertThat(q.reserve("y", Long.MAX_VALUE)).isEmpty()
    }

    @Test
    fun `reserved rows are hidden until released`() {
        val q = queue()
        q.enqueue("a", "1")
        q.enqueue("b", "2")
        assertThat(q.reserve("x", Long.MAX_VALUE)).hasSize(2)
        assertThat(q.reserve("y", Long.MAX_VALUE)).isEmpty()
        q.release("x")
        assertThat(q.reserve("y", Long.MAX_VALUE).map { it.messageId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `release only frees its own batch`() {
        val q = queue()
        q.enqueue("a", "1")
        q.reserve("x", Long.MAX_VALUE)
        q.enqueue("b", "2")
        q.reserve("y", Long.MAX_VALUE)
        q.release("x")
        assertThat(q.reserve("z", Long.MAX_VALUE).map { it.messageId }).containsExactly("a")
    }

    @Test
    fun `drops the oldest rows beyond maxSize including reserved ones`() {
        val q = queue(3)
        q.enqueue("1", "1")
        q.reserve("x", Long.MAX_VALUE) // "1" is in flight
        q.enqueue("2", "2")
        q.enqueue("3", "3")
        q.enqueue("4", "4")
        assertThat(q.size()).isEqualTo(3)
        q.ack("x") // nothing left to ack
        assertThat(q.drain()).containsExactly("2", "3", "4").inOrder()
    }

    @Test
    fun `respects custom maxSize`() {
        val q = queue(2)
        q.enqueue("a", "1")
        q.enqueue("b", "2")
        q.enqueue("c", "3")
        assertThat(q.size()).isEqualTo(2)
        assertThat(q.drain()).containsExactly("b", "c").inOrder()
    }

    @Test
    fun `rows persist across a new instance on the same database`() {
        val first = queue()
        first.enqueue("a", "1")
        first.enqueue("b", "2")
        first.close()

        val second = queue()
        assertThat(second.size()).isEqualTo(2)
        assertThat(second.drain()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `releaseAll after a crash makes in-flight rows available again`() {
        val crashed = queue()
        crashed.enqueue("a", "1")
        crashed.enqueue("b", "2")
        crashed.reserve("in-flight", Long.MAX_VALUE)
        crashed.close() // process died mid-send: rows still carry batch_id

        val restarted = queue()
        assertThat(restarted.reserve("x", Long.MAX_VALUE)).isEmpty()
        restarted.releaseAll()
        assertThat(restarted.reserve("x", Long.MAX_VALUE).map { it.messageId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `byte budget splits batches and always takes at least one`() {
        val q = queue()
        q.enqueue("a", "x".repeat(100))
        q.enqueue("b", "x".repeat(100))
        q.enqueue("c", "x".repeat(100))
        q.enqueue("d", "x".repeat(1000))

        assertThat(q.reserve("1", 250).map { it.messageId }).containsExactly("a", "b").inOrder()
        assertThat(q.reserve("2", 250).map { it.messageId }).containsExactly("c")
        assertThat(q.reserve("3", 250).map { it.messageId }).containsExactly("d")
        assertThat(q.reserve("4", 250)).isEmpty()
    }

    @Test
    fun `byte budget counts UTF-8 bytes`() {
        val q = queue()
        q.enqueue("a", "ééééé") // 10 bytes
        q.enqueue("b", "b")
        assertThat(q.reserve("1", 10).map { it.messageId }).containsExactly("a")
    }

    @Test
    fun `duplicate message_id is replaced and moves to the tail`() {
        val q = queue()
        q.enqueue("a", "1")
        q.enqueue("b", "2")
        q.enqueue("a", "1-again")
        assertThat(q.size()).isEqualTo(2)
        val rows = q.reserve("x", Long.MAX_VALUE)
        assertThat(rows.map { it.messageId }).containsExactly("b", "a").inOrder()
        assertThat(rows.last().payload).isEqualTo("1-again")
    }

    @Test
    fun `reserve marks exactly the returned rows`() {
        val q = queue()
        q.enqueue("a", "1")
        q.enqueue("b", "2")
        q.reserve("first", Long.MAX_VALUE) // a, b in flight
        q.enqueue("c", "3")
        q.enqueue("d", "x".repeat(50))
        // budget admits c only; d must remain unreserved even though its id is higher
        assertThat(q.reserve("second", 10).map { it.messageId }).containsExactly("c")
        assertThat(q.reserve("third", Long.MAX_VALUE).map { it.messageId }).containsExactly("d")
        q.ack("first")
        q.ack("second")
        q.ack("third")
        assertThat(q.size()).isEqualTo(0)
    }

    @Test
    fun `payloads with quotes and unicode round-trip`() {
        val q = queue()
        val payload = "{\"event\":\"O'Reilly \\\"quoted\\\" — ünïcödé 🎉\"}"
        q.enqueue("a", payload)
        assertThat(q.reserve("x", Long.MAX_VALUE).single().payload).isEqualTo(payload)
    }

    @Test
    fun `hostile message ids are bound not interpolated`() {
        val q = queue()
        q.enqueue("a'; DROP TABLE events; --", "1")
        q.enqueue("b", "2")
        assertThat(q.size()).isEqualTo(2)
        q.reserve("x' OR '1'='1", Long.MAX_VALUE)
        q.release("nope")
        assertThat(q.reserve("y", Long.MAX_VALUE)).isEmpty()
        q.ack("x' OR '1'='1")
        assertThat(q.size()).isEqualTo(0)
    }
}
