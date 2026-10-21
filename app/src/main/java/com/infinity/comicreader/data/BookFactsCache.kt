package com.infinity.comicreader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.infinity.comicreader.reader.DirectionDetection
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers each book's [BookFacts] with the file size they were read at; a
 * different size means the book changed and its facts are stale. Stored one row
 * per book in a small database, and held in memory once [load]ed.
 */
class BookFactsCache(context: Context) {
    private val store = storeFor(context.applicationContext)

    /** The facts stored for [path] at [size]; null when unknown, stale, or not [load]ed yet. Never blocks. */
    fun get(path: String, size: Long): BookFacts? {
        val (known, facts) = store.loaded?.get(path) ?: return null
        return facts.takeIf { known == size }
    }

    /** True once [load] has run, so [get] answers from everything stored. */
    val isLoaded: Boolean get() = store.loaded != null

    /** Reads every stored row into memory. Blocking; does nothing once loaded. */
    fun load() {
        if (store.loaded != null) return
        synchronized(store) {
            if (store.loaded != null) return
            val map = ConcurrentHashMap<String, Pair<Long, BookFacts>>()
            store.helper.readableDatabase.query(TABLE, arrayOf(PATH, SIZE, PAGES, YEAR, DIRECTION), null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    map[c.getString(0)] = c.getLong(1) to BookFacts(c.getInt(2), c.getInt(3), DirectionDetection.Detected.decode(c.getInt(4)))
                }
            }
            store.loaded = map
        }
    }

    /** Stores [facts] of [path], read at [size]. Blocking. */
    fun put(path: String, size: Long, facts: BookFacts) {
        load()
        store.loaded?.put(path, size to facts)
        val row = ContentValues(5).apply {
            put(PATH, path)
            put(SIZE, size)
            put(PAGES, facts.pages)
            put(YEAR, facts.year)
            put(DIRECTION, facts.detected?.encode() ?: 0)
        }
        store.helper.writableDatabase.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** One database file, opened once, and its rows once loaded. */
    private class Store(val file: String, val helper: Helper) {
        @Volatile
        var loaded: MutableMap<String, Pair<Long, BookFacts>>? = null
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, FILE, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($PATH TEXT PRIMARY KEY, $SIZE INTEGER NOT NULL, $PAGES INTEGER NOT NULL, " +
                    "$YEAR INTEGER NOT NULL, $DIRECTION INTEGER NOT NULL DEFAULT 0)"
            )
        }

        /** Older rows lack what newer versions read, so every book is read again. */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val FILE = "book_facts.db"
        private const val TABLE = "facts"
        private const val PATH = "path"
        private const val SIZE = "size"
        private const val PAGES = "pages"
        private const val YEAR = "year"
        private const val DIRECTION = "direction"

        /** 2 added the detected direction. */
        private const val VERSION = 2
        private val LOCK = Any()

        /** Shared by every instance: one process, one database (a new one when its file moves, as between tests). */
        @Volatile
        private var shared: Store? = null

        private fun storeFor(context: Context): Store {
            val file = context.getDatabasePath(FILE).path
            shared?.takeIf { it.file == file }?.let { return it }
            synchronized(LOCK) {
                shared?.let { if (it.file == file) return it else it.helper.close() }
                return Store(file, Helper(context)).also { shared = it }
            }
        }

        /** Forgets the in-memory copy and closes the database; tests start each case from disk. */
        internal fun reset() {
            synchronized(LOCK) {
                shared?.helper?.close()
                shared = null
            }
        }
    }
}
