package com.infinity.comicreader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the [BookId] found for each file by its path, size and modification time,
 * so a file seen before costs no read: a different size or time means it changed (or
 * another file took its place) and is hashed again. Stored one row per path in a small
 * database and held in memory once [load]ed; every instance shares one store.
 */
class BookIndex(context: Context) {
    private val store = storeFor(context.applicationContext)

    /** One file's row: its size and modification time when hashed, and the ID found. */
    private class Row(val size: Long, val modified: Long, val id: Long)

    /** The ID found for [path] at [size] and [modified]; null when unknown or stale. Loads first when needed. */
    fun get(path: String, size: Long, modified: Long): BookId? {
        val row = loaded()[path] ?: return null
        return if (row.size == size && row.modified == modified) BookId(row.id) else null
    }

    /** The ID last found for [path], whatever its size and time then; null when never hashed. */
    fun last(path: String): BookId? = loaded()[path]?.let { BookId(it.id) }

    /** The IDs last found for [root], a file or folder, and everything below it. */
    fun under(root: String): Set<BookId> = loaded().entries.filter { under(it.key, root) }.mapTo(HashSet()) { BookId(it.value.id) }

    /** Remembers [id] for [path] at [size] and [modified]. Blocking. */
    fun put(path: String, size: Long, modified: Long, id: BookId) {
        val map = loaded()
        val known = map[path]
        if (known != null && known.size == size && known.modified == modified && known.id == id.value) return
        map[path] = Row(size, modified, id.value)
        val row = ContentValues(4).apply {
            put(PATH, path)
            put(SIZE, size)
            put(MODIFIED, modified)
            put(ID, id.value)
        }
        store.helper.writableDatabase.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Re-points the rows of [old], a file or folder renamed or moved by the app, to [new]. Blocking. */
    fun move(old: String, new: String) {
        val map = loaded()
        val moved = map.keys.filter { under(it, old) }
        if (moved.isEmpty()) return
        val db = store.helper.writableDatabase
        db.beginTransaction()
        try {
            for (p in moved) {
                val row = map.remove(p) ?: continue
                val to = new + p.removePrefix(old)
                map[to] = row
                db.delete(TABLE, "$PATH = ?", arrayOf(p))
                db.insertWithOnConflict(
                    TABLE, null,
                    ContentValues(4).apply {
                        put(PATH, to)
                        put(SIZE, row.size)
                        put(MODIFIED, row.modified)
                        put(ID, row.id)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Forgets the rows of [root], a file or folder deleted or replaced, and of everything below it. Blocking. */
    fun remove(root: String) {
        val map = loaded()
        val gone = map.keys.filter { under(it, root) }
        if (gone.isEmpty()) return
        val db = store.helper.writableDatabase
        db.beginTransaction()
        try {
            for (p in gone) {
                map.remove(p)
                db.delete(TABLE, "$PATH = ?", arrayOf(p))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Reads every stored row into memory. Blocking; does nothing once loaded. */
    fun load() {
        loaded()
    }

    private fun loaded(): MutableMap<String, Row> {
        store.loaded?.let { return it }
        synchronized(store) {
            store.loaded?.let { return it }
            val map = ConcurrentHashMap<String, Row>()
            store.helper.readableDatabase.query(TABLE, arrayOf(PATH, SIZE, MODIFIED, ID), null, null, null, null, null).use { c ->
                while (c.moveToNext()) map[c.getString(0)] = Row(c.getLong(1), c.getLong(2), c.getLong(3))
            }
            store.loaded = map
            return map
        }
    }

    /** One database file, opened once, and its rows once loaded. */
    private class Store(val file: String, val helper: Helper) {
        @Volatile
        var loaded: MutableMap<String, Row>? = null
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, FILE, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $TABLE ($PATH TEXT PRIMARY KEY, $SIZE INTEGER NOT NULL, $MODIFIED INTEGER NOT NULL, $ID INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val FILE = "book_ids.db"
        private const val TABLE = "ids"
        private const val PATH = "path"
        private const val SIZE = "size"
        private const val MODIFIED = "modified"
        private const val ID = "id"
        private const val VERSION = 1
        private val LOCK = Any()

        /** True when [path] is [root] or below it. */
        internal fun under(path: String, root: String) =
            path == root || (path.length > root.length && path.startsWith(root) && isSeparator(path[root.length]))

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
