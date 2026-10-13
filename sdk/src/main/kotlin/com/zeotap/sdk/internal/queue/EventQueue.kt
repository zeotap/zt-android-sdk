package com.zeotap.sdk.internal.queue

/** A queued, fully-serialised event. `payload` is the final JSON bytes as a UTF-8 string. */
internal data class QueuedEvent(val id: Long, val messageId: String, val payload: String)

/**
 * Durable FIFO event queue with reservation semantics (the deliberate mobile
 * divergence from the web SDK's in-memory `Queue`):
 *
 *  - [enqueue] appends; when [size] would exceed `maxSize` the OLDEST rows are
 *    deleted first (web `queue.ts` drop-oldest, made durable).
 *  - [reserve] marks unreserved rows (oldest first, up to `maxBytes` of payload)
 *    with `batchId` and returns them; a reserved row is invisible to later
 *    reserves until [ack]ed (deleted) or [release]d (made available again).
 *  - [releaseAll] is called at start-up: rows still reserved belonged to a
 *    process that died mid-send and must be resent (at-least-once).
 *
 * Implementations are used from the SDK's single thread only.
 */
internal interface EventQueue {
    fun enqueue(messageId: String, payload: String)
    fun reserve(batchId: String, maxBytes: Long): List<QueuedEvent>
    fun ack(batchId: String)
    fun release(batchId: String)
    fun releaseAll()
    fun size(): Int
    fun clear()
    fun close()
}
