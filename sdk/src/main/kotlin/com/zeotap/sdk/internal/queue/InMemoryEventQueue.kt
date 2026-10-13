package com.zeotap.sdk.internal.queue

/**
 * Non-durable [EventQueue] with exactly the semantics of [SqliteEventQueue]
 * (web `queue.ts` drop-oldest, plus reservation) — used by client tests and
 * as the fallback when the SQLite store cannot be opened.
 */
internal class InMemoryEventQueue(private val maxSize: Int) : EventQueue {
    private class Row(val id: Long, val messageId: String, val payload: String, var batchId: String?)

    private val rows = ArrayList<Row>()
    private var nextId = 1L

    override fun enqueue(messageId: String, payload: String) {
        // INSERT OR REPLACE: a re-enqueued messageId moves to the tail with a new id.
        rows.removeAll { it.messageId == messageId }
        rows.add(Row(nextId++, messageId, payload, null))
        // Drop-oldest is a hard cap: reserved rows count and are evicted too.
        val excess = rows.size - maxSize
        if (excess > 0) rows.subList(0, excess).clear()
    }

    override fun reserve(batchId: String, maxBytes: Long): List<QueuedEvent> {
        val taken = ArrayList<QueuedEvent>()
        var bytes = 0L
        for (row in rows) {
            if (row.batchId != null) continue
            val size = PayloadBytes.utf8Length(row.payload).toLong()
            // Always take at least one row so an oversized payload cannot wedge the queue.
            if (taken.isNotEmpty() && bytes + size > maxBytes) break
            row.batchId = batchId
            taken.add(QueuedEvent(row.id, row.messageId, row.payload))
            bytes += size
        }
        return taken
    }

    override fun ack(batchId: String) {
        rows.removeAll { it.batchId == batchId }
    }

    override fun release(batchId: String) {
        for (row in rows) if (row.batchId == batchId) row.batchId = null
    }

    override fun releaseAll() {
        for (row in rows) row.batchId = null
    }

    override fun size(): Int = rows.size

    override fun clear() {
        rows.clear()
    }

    override fun close() = Unit
}
