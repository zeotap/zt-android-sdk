package com.zeotap.sdk.internal.queue

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.zeotap.sdk.internal.queue.EventsDbHelper.Companion.TABLE

/**
 * Durable [EventQueue] over `android.database.sqlite` — the deliberate mobile
 * divergence from the web SDK's in-memory queue. Every statement binds its
 * values as parameters; no value is ever interpolated into SQL.
 *
 * Failure policy: `SQLiteException`s propagate to the caller (the engine
 * reports them through `SdkListener.onError`); nothing is swallowed here.
 * Used from the SDK's single thread only.
 */
internal class SqliteEventQueue(context: Context, dbName: String, private val maxSize: Int) : EventQueue {
    private val helper = EventsDbHelper(context.applicationContext, dbName)

    private val db: SQLiteDatabase
        get() = helper.writableDatabase

    override fun enqueue(messageId: String, payload: String) {
        val database = db
        database.beginTransaction()
        try {
            // REPLACE deletes the row with the same message_id and inserts a
            // fresh one (new autoincrement id) — a re-enqueue moves to the tail.
            database.compileStatement(
                "INSERT OR REPLACE INTO $TABLE (message_id, payload, created_at, batch_id) VALUES (?, ?, ?, NULL)",
            ).use { stmt ->
                stmt.bindString(1, messageId)
                stmt.bindString(2, payload)
                stmt.bindLong(3, System.currentTimeMillis())
                stmt.executeInsert()
            }
            // Hard cap: drop the OLDEST rows — reserved ones included — beyond maxSize.
            val excess = DatabaseUtils.queryNumEntries(database, TABLE) - maxSize
            if (excess > 0) {
                database.execSQL(
                    "DELETE FROM $TABLE WHERE id IN (SELECT id FROM $TABLE ORDER BY id ASC LIMIT ?)",
                    arrayOf<Any>(excess),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override fun reserve(batchId: String, maxBytes: Long): List<QueuedEvent> {
        val database = db
        val taken = ArrayList<QueuedEvent>()
        database.beginTransaction()
        try {
            database.rawQuery(
                "SELECT id, message_id, payload FROM $TABLE WHERE batch_id IS NULL ORDER BY id ASC",
                null,
            ).use { cursor ->
                var bytes = 0L
                while (cursor.moveToNext()) {
                    val payload = cursor.getString(2)
                    val size = PayloadBytes.utf8Length(payload).toLong()
                    // Always take at least one row so an oversized payload cannot wedge the queue.
                    if (taken.isNotEmpty() && bytes + size > maxBytes) break
                    taken.add(QueuedEvent(cursor.getLong(0), cursor.getString(1), payload))
                    bytes += size
                }
            }
            if (taken.isNotEmpty()) {
                // The taken rows are exactly the unreserved rows with id <= the
                // last one read (a prefix in id order, inside this transaction),
                // so a range predicate replaces an IN-list and sidesteps
                // SQLite's 999-variable limit on old devices.
                database.execSQL(
                    "UPDATE $TABLE SET batch_id = ? WHERE batch_id IS NULL AND id <= ?",
                    arrayOf<Any>(batchId, taken.last().id),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return taken
    }

    override fun ack(batchId: String) {
        db.delete(TABLE, "batch_id = ?", arrayOf(batchId))
    }

    override fun release(batchId: String) {
        db.execSQL("UPDATE $TABLE SET batch_id = NULL WHERE batch_id = ?", arrayOf<Any>(batchId))
    }

    override fun releaseAll() {
        db.execSQL("UPDATE $TABLE SET batch_id = NULL WHERE batch_id IS NOT NULL")
    }

    override fun size(): Int = DatabaseUtils.queryNumEntries(db, TABLE).toInt()

    override fun clear() {
        db.delete(TABLE, null, null)
    }

    override fun close() {
        helper.close()
    }
}
