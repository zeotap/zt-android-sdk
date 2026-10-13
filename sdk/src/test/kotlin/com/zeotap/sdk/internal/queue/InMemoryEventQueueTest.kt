package com.zeotap.sdk.internal.queue

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Mirrors `sdk/js/src/queue.test.ts` plus the mobile reservation semantics. */
class InMemoryEventQueueTest {
    private fun queue(maxSize: Int = 500) = InMemoryEventQueue(maxSize)

    private fun EventQueue.drain(batchId: String = "b"): List<String> {
        val rows = reserve(batchId, Long.MAX_VALUE)
        ack(batchId)
        return rows.map { it.messageId }
    }

    @Test
    fun `enqueue increases size`() {
        val q = queue()
        assertThat(q.size()).isEqualTo(0)
        q.enqueue("a", "{\"messageId\":\"a\"}")
        assertThat(q.size()).isEqualTo(1)
        q.enqueue("b", "{\"messageId\":\"b\"}")
        assertThat(q.size()).isEqualTo(2)
    }

    @Test
    fun `multiple enqueues maintain FIFO order`() {
        val q = queue()
        q.enqueue("first", "1")
        q.enqueue("second", "2")
        q.enqueue("third", "3")
        assertThat(q.drain()).containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun `reserve then ack returns all items and empties the queue`() {
        val q = queue()
        q.enqueue("a", "1")
        q.enqueue("b", "2")
        val rows = q.reserve("batch", Long.MAX_VALUE)
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.payload }).containsExactly("1", "2").inOrder()
        q.ack("batch")
        assertThat(q.size()).isEqualTo(0)
    }

    @Test
    fun `reserve on empty queue returns empty list`() {
        assertThat(queue().reserve("b", Long.MAX_VALUE)).isEmpty()
    }

    @Test
    fun `clear resets size to 0`() {
        val q = queue()
        q.enqueue("a", "1")
        q.enqueue("b", "2")
        q.clear()
        assertThat(q.size()).isEqualTo(0)
        assertThat(q.reserve("b", Long.MAX_VALUE)).isEmpty()
    }

    @Test
    fun `drops the oldest item when maxSize is reached`() {
        val q = queue(3)
        q.enqueue("1", "1")
        q.enqueue("2", "2")
        q.enqueue("3", "3")
        q.enqueue("4", "4")
        assertThat(q.size()).isEqualTo(3)
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
    fun `reserved rows are invisible to later reserves until released`() {
        val q = queue()
        q.enqueue("a", "1")
        q.enqueue("b", "2")
        val first = q.reserve("x", Long.MAX_VALUE)
        assertThat(first.map { it.messageId }).containsExactly("a", "b").inOrder()
        assertThat(q.reserve("y", Long.MAX_VALUE)).isEmpty()
        assertThat(q.size()).isEqualTo(2)

        q.release("x")
        assertThat(q.reserve("y", Long.MAX_VALUE).map { it.messageId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `ack removes only the acked batch`() {
        val q = queue()
        q.enqueue("a", "1")
        q.reserve("x", Long.MAX_VALUE)
        q.enqueue("b", "2")
        q.reserve("y", Long.MAX_VALUE)
        q.ack("x")
        assertThat(q.size()).isEqualTo(1)
        q.release("y")
        assertThat(q.drain().single()).isEqualTo("b")
    }

    @Test
    fun `releaseAll frees every reservation`() {
        val q = queue()
        q.enqueue("a", "1")
        q.reserve("x", Long.MAX_VALUE)
        q.enqueue("b", "2")
        q.reserve("y", Long.MAX_VALUE)
        q.releaseAll()
        assertThat(q.reserve("z", Long.MAX_VALUE).map { it.messageId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `reserve respects the byte budget and always takes at least one`() {
        val q = queue()
        q.enqueue("a", "x".repeat(10))
        q.enqueue("b", "x".repeat(10))
        q.enqueue("c", "x".repeat(10))
        val first = q.reserve("1", 20)
        assertThat(first.map { it.messageId }).containsExactly("a", "b").inOrder()
        val second = q.reserve("2", 5) // oversized single row still taken
        assertThat(second.map { it.messageId }).containsExactly("c")
    }

    @Test
    fun `byte budget counts UTF-8 bytes not chars`() {
        val q = queue()
        q.enqueue("a", "ééééé") // 5 chars, 10 bytes
        q.enqueue("b", "b")
        assertThat(q.reserve("1", 10).map { it.messageId }).containsExactly("a")
    }

    @Test
    fun `re-enqueueing a messageId replaces the row and moves it to the tail`() {
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
    fun `drop-oldest counts reserved rows`() {
        val q = queue(2)
        q.enqueue("a", "1")
        q.reserve("x", Long.MAX_VALUE)
        q.enqueue("b", "2")
        q.enqueue("c", "3")
        assertThat(q.size()).isEqualTo(2)
        q.ack("x") // "a" was already evicted; ack is a no-op
        assertThat(q.drain()).containsExactly("b", "c").inOrder()
    }

    @Test
    fun `ids are monotonically increasing`() {
        val q = queue()
        q.enqueue("a", "1")
        q.enqueue("b", "2")
        val rows = q.reserve("x", Long.MAX_VALUE)
        assertThat(rows[0].id).isLessThan(rows[1].id)
    }
}
