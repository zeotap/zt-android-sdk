package com.zeotap.sdk.internal.queue

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Schema owner for the durable event queue. One table:
 *
 * ```
 * events(id INTEGER PRIMARY KEY AUTOINCREMENT, message_id TEXT NOT NULL UNIQUE,
 *        payload TEXT NOT NULL, created_at INTEGER NOT NULL, batch_id TEXT)
 * ```
 *
 * `id` is the FIFO order, `message_id` the at-least-once dedupe key,
 * `batch_id` the in-flight reservation (NULL = available).
 */
internal class EventsDbHelper(context: Context, dbName: String) :
    SQLiteOpenHelper(context, dbName, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "message_id TEXT NOT NULL UNIQUE, " +
                "payload TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "batch_id TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_batch_id ON $TABLE(batch_id)")
    }

    /**
     * Migration rule: ADDITIVE ONLY. A future version may `ALTER TABLE … ADD
     * COLUMN` or create new tables/indexes; it must never drop or rewrite
     * `events`, because rows still in it are a user's unsent events and a
     * migration that loses them is data loss. Nothing to do for v1.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** A downgraded SDK still understands every additive schema — keep the rows. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val VERSION: Int = 1
        const val TABLE: String = "events"
    }
}
