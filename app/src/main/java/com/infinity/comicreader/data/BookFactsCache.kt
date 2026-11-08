package com.infinity.comicreader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.infinity.comicreader.reader.DirectionDetection
import com.infinity.comicreader.reader.Slices
import com.infinity.comicreader.reader.Wraparound
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers each book's [BookFacts] by its [BookId], so they follow the book moved,
 * renamed or copied, and a changed book (another ID) never reads stale ones. Stored
 * one row per book in a small database, and held in memory once [load]ed.
 */
class BookFactsCache(context: Context) {
    private val store = storeFor(context.applicationContext)

    /** The facts stored for book [id]; null when unknown, not [load]ed yet, or [id] is. Never blocks. */
    fun get(id: BookId?): BookFacts? = id?.let { store.loaded?.get(it.value) }

    /** True once [load] has run, so [get] answers from everything stored. */
    val isLoaded: Boolean get() = store.loaded != null

    /** Reads every stored row into memory. Blocking; does nothing once loaded. */
    fun load() {
        if (store.loaded != null) return
        synchronized(store) {
            if (store.loaded != null) return
            val map = ConcurrentHashMap<Long, BookFacts>()
            store.helper.readableDatabase.query(TABLE, arrayOf(ID, PAGES, YEAR, DIRECTION, SLICES, COVER, COVER_RULES), null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    val cover = if (c.getInt(6) == SmartCrop.RULES) decodeCover(c.getInt(5)) else null
                    map[c.getLong(0)] = BookFacts(c.getInt(1), c.getInt(2), DirectionDetection.Detected.decode(c.getInt(3)), decodeSlices(c.getInt(4)), cover)
                }
            }
            store.loaded = map
        }
    }

    /**
     * Stores [facts] of book [id], keeping what its pages were found to be ([Slices]) and
     * what its cover told ([BookFacts.cover]) when [facts] do not say. Blocking.
     */
    fun put(id: BookId, facts: BookFacts) {
        load()
        synchronized(store) {
            val known = store.loaded?.get(id.value)
            write(id, facts.copy(slices = facts.slices ?: known?.slices, cover = facts.cover ?: known?.cover))
        }
    }

    /** Sets what book [id]'s pages were found to be on its stored facts; nothing without any. Blocking. */
    fun keepSlices(id: BookId, checked: Slices.Checked) {
        load()
        synchronized(store) {
            val known = store.loaded?.get(id.value) ?: return
            if (known.slices != checked) write(id, known.copy(slices = checked))
        }
    }

    /** Sets what book [id]'s cover told of its reading direction on its stored facts; nothing without any. Blocking. */
    fun keepCover(id: BookId, front: CoverFront) {
        load()
        synchronized(store) {
            val known = store.loaded?.get(id.value) ?: return
            if (known.cover != front) write(id, known.copy(cover = front))
        }
    }

    /** Stores [facts] of book [id] as they are, in memory and on disk. */
    private fun write(id: BookId, facts: BookFacts) {
        store.loaded?.put(id.value, facts)
        val row = ContentValues(7).apply {
            put(ID, id.value)
            put(PAGES, facts.pages)
            put(YEAR, facts.year)
            put(DIRECTION, facts.detected?.encode() ?: 0)
            put(SLICES, encodeSlices(facts.slices))
            put(COVER, encodeCover(facts.cover))
            put(COVER_RULES, SmartCrop.RULES)
        }
        store.helper.writableDatabase.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Forgets the facts of book [id], as when what they were read from changes. Blocking. */
    fun remove(id: BookId) {
        load()
        synchronized(store) {
            store.loaded?.remove(id.value)
            store.helper.writableDatabase.delete(TABLE, "$ID = ?", arrayOf(id.value.toString()))
        }
    }

    /** Forgets the facts of every book; each is read again when next needed. Blocking. */
    fun clear() {
        synchronized(store) {
            store.helper.writableDatabase.delete(TABLE, null, null)
            store.loaded = ConcurrentHashMap()
        }
    }

    /** One database file, opened once, and its rows once loaded. */
    private class Store(val file: String, val helper: Helper) {
        @Volatile
        var loaded: MutableMap<Long, BookFacts>? = null
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, FILE, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($ID INTEGER PRIMARY KEY, $PAGES INTEGER NOT NULL, " +
                    "$YEAR INTEGER NOT NULL, $DIRECTION INTEGER NOT NULL DEFAULT 0, $SLICES INTEGER NOT NULL DEFAULT $UNCHECKED, " +
                    "$COVER INTEGER NOT NULL DEFAULT $UNCHECKED, $COVER_RULES INTEGER NOT NULL DEFAULT 0)"
            )
        }

        /** A cache: every book is read again. */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val FILE = "book_facts.db"
        private const val TABLE = "facts"
        private const val ID = "id"
        private const val PAGES = "pages"
        private const val YEAR = "year"
        private const val DIRECTION = "direction"
        private const val SLICES = "slices"
        private const val COVER = "cover"

        /** The [SmartCrop.RULES] [COVER] was found by; covers found by others are looked at again. */
        private const val COVER_RULES = "cover_rules"

        /** [SLICES] of a book not checked for slices, [COVER] of one whose cover was not looked at; 0 is one whose pages are not, or whose cover tells nothing. */
        private const val UNCHECKED = -1

        /** Of the table and what its numbers mean ([DirectionDetection.Detected.encode]); another starts it over. */
        private const val VERSION = 7

        private fun encodeSlices(checked: Slices.Checked?): Int = checked?.let { it.found?.encode() ?: 0 } ?: UNCHECKED

        private fun decodeSlices(value: Int): Slices.Checked? =
            if (value == 0) Slices.Checked(null) else DirectionDetection.Detected.decode(value)?.let { Slices.Checked(it) }

        /** A [CoverFront] as one number: its page times 3 plus 0 for no half, else 1 + the half's ordinal. */
        private fun encodeCover(front: CoverFront?): Int = front?.let { it.page * 3 + (it.half?.ordinal?.plus(1) ?: 0) } ?: UNCHECKED

        private fun decodeCover(value: Int): CoverFront? =
            if (value < 0) null else CoverFront(value / 3, Wraparound.Half.entries.getOrNull(value % 3 - 1))
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
