package com.polaris.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Entry(val id: Long, val url: String, val title: String, val ts: Long)
data class Dl(val id: Long, val sysId: Long, val name: String, val url: String, val mime: String, val ts: Long,
              val state: Int = -1, val total: Long = 0, val uri: String = "")

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "polaris.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT UNIQUE, title TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT, title TEXT, ts INTEGER)")
        db.execSQL("CREATE INDEX hist_ts ON history(ts)")
        db.execSQL("CREATE TABLE downloads(id INTEGER PRIMARY KEY AUTOINCREMENT, sys INTEGER, name TEXT, url TEXT, mime TEXT, ts INTEGER, state INTEGER DEFAULT -1, total INTEGER DEFAULT 0, uri TEXT)")
        // Starter speed-dial
        listOf(
            "https://www.youtube.com" to "YouTube",
            "https://vk.com" to "VK", "https://github.com" to "GitHub",
            "https://ru.wikipedia.org" to "Википедия", "https://habr.com" to "Хабр",
        ).forEachIndexed { i, (u, t) ->
            db.insert("bookmarks", null, ContentValues().apply { put("url", u); put("title", t); put("ts", i.toLong()) })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) db.execSQL("DELETE FROM bookmarks WHERE url LIKE '%ya.ru%' OR url LIKE '%yandex.%'")
        if (o < 3) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN state INTEGER DEFAULT -1")
            db.execSQL("ALTER TABLE downloads ADD COLUMN total INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE downloads ADD COLUMN uri TEXT")
        }
    }

    fun bookmarks(): List<Entry> = readableDatabase.rawQuery("SELECT id,url,title,ts FROM bookmarks ORDER BY ts", null).use { c ->
        buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3))) }
    }

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url=?", arrayOf(url)).use { it.moveToFirst() }

    fun addBookmark(url: String, title: String) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("url", url); put("title", title); put("ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeBookmark(url: String) { writableDatabase.delete("bookmarks", "url=?", arrayOf(url)) }

    fun renameBookmark(id: Long, title: String) {
        writableDatabase.update("bookmarks", ContentValues().apply { put("title", title) }, "id=?", arrayOf(id.toString()))
    }

    fun addHistory(url: String, title: String) {
        val db = writableDatabase
        // Collapse consecutive reloads of the same page
        db.rawQuery("SELECT id,url FROM history ORDER BY ts DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst() && c.getString(1) == url) {
                db.update("history", ContentValues().apply { put("title", title); put("ts", System.currentTimeMillis()) },
                    "id=?", arrayOf(c.getLong(0).toString()))
                return
            }
        }
        db.insert("history", null, ContentValues().apply {
            put("url", url); put("title", title); put("ts", System.currentTimeMillis())
        })
    }

    fun updateHistoryTitle(url: String, title: String) {
        writableDatabase.execSQL(
            "UPDATE history SET title=? WHERE id=(SELECT id FROM history WHERE url=? ORDER BY ts DESC LIMIT 1)",
            arrayOf(title, url)
        )
    }

    fun history(query: String = "", limit: Int = 300): List<Entry> {
        val (sql, args) = if (query.isBlank())
            "SELECT id,url,title,ts FROM history ORDER BY ts DESC LIMIT $limit" to null
        else "SELECT id,url,title,ts FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY ts DESC LIMIT $limit" to
                arrayOf("%$query%", "%$query%")
        return readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getLong(3))) }
        }
    }

    /** Suggestions for the address bar: bookmarks first, then frequently visited. */
    fun suggest(q: String, limit: Int = 6): List<Entry> {
        if (q.isBlank()) return emptyList()
        val a = arrayOf("%$q%", "%$q%")
        val out = LinkedHashMap<String, Entry>()
        readableDatabase.rawQuery("SELECT id,url,title,ts FROM bookmarks WHERE url LIKE ? OR title LIKE ? LIMIT 3", a).use { c ->
            while (c.moveToNext()) out[c.getString(1)] = Entry(c.getLong(0), c.getString(1), c.getString(2) ?: "", -1)
        }
        readableDatabase.rawQuery(
            "SELECT 0,url,MAX(title),COUNT(*) n FROM history WHERE url LIKE ? OR title LIKE ? GROUP BY url ORDER BY n DESC LIMIT 10", a
        ).use { c ->
            while (c.moveToNext() && out.size < limit) out.putIfAbsent(c.getString(1), Entry(0, c.getString(1), c.getString(2) ?: "", 0))
        }
        return out.values.toList()
    }

    fun deleteHistory(id: Long) { writableDatabase.delete("history", "id=?", arrayOf(id.toString())) }
    fun clearHistory() { writableDatabase.delete("history", null, null) }

    fun addDownload(sys: Long, name: String, url: String, mime: String): Long =
        writableDatabase.insert("downloads", null, ContentValues().apply {
            put("sys", sys); put("name", name); put("url", url); put("mime", mime); put("ts", System.currentTimeMillis())
            if (sys == 0L) put("state", 0)
        })

    fun finishDownload(id: Long, name: String, mime: String, total: Long, uri: String, state: Int) {
        writableDatabase.update("downloads", ContentValues().apply {
            put("name", name); put("mime", mime); put("total", total); put("uri", uri); put("state", state)
        }, "id=?", arrayOf(id.toString()))
    }

    fun downloads(): List<Dl> = readableDatabase.rawQuery("SELECT id,sys,name,url,mime,ts,state,total,uri FROM downloads ORDER BY ts DESC LIMIT 200", null).use { c ->
        buildList { while (c.moveToNext()) add(Dl(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4) ?: "", c.getLong(5),
            if (c.isNull(6)) -1 else c.getInt(6), c.getLong(7), c.getString(8) ?: "")) }
    }

    fun deleteDownload(id: Long) { writableDatabase.delete("downloads", "id=?", arrayOf(id.toString())) }
}
