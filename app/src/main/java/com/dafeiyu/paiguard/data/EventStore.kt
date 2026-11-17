package com.dafeiyu.paiguard.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 事件库。只在模块进程里读写（宿主通过 ContentProvider 送进来）。
 * 只留最近 500 条，防护日志不是审计系统，别把用户手机塞满。
 */
class EventStore private constructor(context: Context) {

    private val helper = Helper(context.applicationContext)

    fun insert(kind: String, title: String, detail: String, count: Int, time: Long): Long {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("kind", kind)
            put("title", title)
            put("detail", detail)
            put("count", count)
            put("time", time)
        }
        val id = try {
            db.insert("events", null, values)
        } catch (_: Throwable) {
            -1L
        }
        trim(db)
        return id
    }

    fun recent(limit: Int = 200): List<Event> {
        val db = helper.readableDatabase
        val out = ArrayList<Event>(limit)
        db.rawQuery(
            "SELECT id, kind, title, detail, count, time FROM events ORDER BY time DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Event(
                        id = c.getLong(0),
                        kind = c.getString(1),
                        title = c.getString(2),
                        detail = c.getString(3),
                        count = c.getInt(4),
                        time = c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    fun clear() {
        try {
            helper.writableDatabase.delete("events", null, null)
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun summary(): Summary {
        val since = System.currentTimeMillis() - 24 * 3600 * 1000L
        val db = helper.readableDatabase
        var blocked = 0
        var formulas = 0
        db.rawQuery(
            "SELECT COUNT(*), IFNULL(SUM(count),0) FROM events WHERE time >= ? AND kind != 'info'",
            arrayOf(since.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                blocked = c.getInt(0)
                formulas = c.getInt(1)
            }
        }
        return Summary(blocked, formulas)
    }

    private fun trim(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "DELETE FROM events WHERE id NOT IN " +
                    "(SELECT id FROM events ORDER BY time DESC LIMIT 500)"
            )
        } catch (_: Throwable) {
            // ignore
        }
    }

    data class Summary(val blocked: Int, val formulas: Int)

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "paiguard_events.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    title TEXT NOT NULL,
                    detail TEXT,
                    count INTEGER NOT NULL DEFAULT 0,
                    time INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_events_time ON events(time)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS events")
            onCreate(db)
        }
    }

    companion object {
        @Volatile
        private var instance: EventStore? = null

        fun get(context: Context): EventStore =
            instance ?: synchronized(this) {
                instance ?: EventStore(context).also { instance = it }
            }
    }
}
