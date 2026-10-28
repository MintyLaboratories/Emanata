package com.infinity.comicreader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Each opened book's [Likeness] by its [BookId], with the file name it was taken under
 * (a weak likeness depends on it) and when, so a re-encoded copy opened later can be
 * matched to the book whose data it may take over. Library data: it goes with the
 * book's data ([remove], [clear]), never with the cache. One row per book in a small
 * database; every instance shares one connection.
 */
class LikenessStore(context: Context) {
    private val store = storeFor(context.applicationContext)

    /** A book's likeness as stored: taken from its file named [fileName] at [time]. */
    data class Row(val id: BookId, val likeness: Likeness, val fileName: String, val time: Long)

    /** What is stored for book [id]; null when nothing is. Blocking. */
    fun get(id: BookId): Row? = query("$ID = ?", arrayOf(id.value.toString())).firstOrNull()

    /** Stores [likeness] of book [id], taken from its file named [fileName] at [time]. Blocking. */
    fun put(id: BookId, likeness: Likeness, fileName: String, time: Long = System.currentTimeMillis()) {
        val row = ContentValues(6).apply {
            put(ID, id.value)
            put(PRINT, likeness.print)
            put(STRONG, if (likeness.strong) 1 else 0)
            put(STEMS, likeness.stems.joinToString(SEP))
            put(NAME, fileName)
            put(TIME, time)
        }
        store.helper.writableDatabase.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The books other than [except] stored with [likeness]'s print, latest first. Blocking. */
    fun alike(likeness: Likeness, except: BookId): List<Row> =
        query("$PRINT = ? AND $ID != ?", arrayOf(likeness.print, except.value.toString()), "$TIME DESC")

    /** Forgets the likeness of books [ids]. Blocking. */
    fun remove(ids: Collection<BookId>) {
        if (ids.isEmpty()) return
        val db = store.helper.writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete(TABLE, "$ID = ?", arrayOf(id.value.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Forgets every likeness. Blocking. */
    fun clear() {
        store.helper.writableDatabase.delete(TABLE, null, null)
    }

    private fun query(where: String, args: Array<String>, order: String? = null): List<Row> {
        val out = ArrayList<Row>()
        store.helper.readableDatabase.query(TABLE, arrayOf(ID, PRINT, STRONG, STEMS, NAME, TIME), where, args, null, null, order).use { c ->
            while (c.moveToNext()) {
                val stems = c.getString(3).let { if (it.isEmpty()) emptyList() else it.split(SEP) }
                out += Row(BookId(c.getLong(0)), Likeness(c.getInt(2) != 0, c.getString(1), stems), c.getString(4), c.getLong(5))
            }
        }
        return out
    }

    private class Store(val file: String, val helper: Helper)

    private class Helper(context: Context) : SQLiteOpenHelper(context, FILE, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($ID INTEGER PRIMARY KEY, $PRINT TEXT NOT NULL, $STRONG INTEGER NOT NULL, " +
                    "$STEMS TEXT NOT NULL, $NAME TEXT NOT NULL, $TIME INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX ${TABLE}_$PRINT ON $TABLE ($PRINT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val FILE = "likeness.db"
        private const val TABLE = "likeness"
        private const val ID = "id"
        private const val PRINT = "print"
        private const val STRONG = "strong"
        private const val STEMS = "stems"
        private const val NAME = "name"
        private const val TIME = "time"
        private const val VERSION = 1

        /** Between page stems as stored; a stem holding one only maps its page by place. */
        private const val SEP = "\n"
        private val LOCK = Any()

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
    }
}
