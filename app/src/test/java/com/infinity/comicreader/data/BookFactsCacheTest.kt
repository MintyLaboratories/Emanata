package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.DirectionDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookFactsCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BookFactsCache.reset()
    }

    @Test
    fun factsAreKeptPerSizeAndAcrossInstances() {
        BookFactsCache(context).put("/b/a.cbz", 100L, BookFacts(24, 2014))
        assertEquals(BookFacts(24, 2014), BookFactsCache(context).get("/b/a.cbz", 100))
        // A different size means the book changed.
        assertNull(BookFactsCache(context).get("/b/a.cbz", 101))
        assertNull(BookFactsCache(context).get("/b/other.cbz", 100))
        BookFactsCache(context).put("/b/a.cbz", 101L, BookFacts(25, 2014))
        assertEquals(BookFacts(25, 2014), BookFactsCache(context).get("/b/a.cbz", 101))
    }

    @Test
    fun detectedDirectionIsKept() {
        val detected = DirectionDetection.Detected(ReadingDirection.RTL, DirectionDetection.Source.PUBLISHER)
        BookFactsCache(context).put("/b/m.cbz", 5L, BookFacts(20, 0, detected))
        BookFactsCache.reset()
        val cache = BookFactsCache(context).apply { load() }
        assertEquals(BookFacts(20, 0, detected), cache.get("/b/m.cbz", 5))
    }

    @Test
    fun olderDatabaseIsReadAgain() {
        // The first version had no direction column.
        val file = context.getDatabasePath("book_facts.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE facts (path TEXT PRIMARY KEY, size INTEGER NOT NULL, pages INTEGER NOT NULL, year INTEGER NOT NULL)")
            db.execSQL("INSERT INTO facts VALUES ('/b/a.cbz', 1, 12, 2001)")
            db.version = 1
        }
        val cache = BookFactsCache(context).apply { load() }
        assertNull(cache.get("/b/a.cbz", 1))
        cache.put("/b/a.cbz", 1L, BookFacts(12, 2001))
        assertEquals(BookFacts(12, 2001), cache.get("/b/a.cbz", 1))
    }

    @Test
    fun factsSurviveARestartOnceLoaded() {
        BookFactsCache(context).put("/b/a.cbz", 7L, BookFacts(-1, 0))
        BookFactsCache.reset()
        val cache = BookFactsCache(context)
        // Looking up never reads the disk.
        assertFalse(cache.isLoaded)
        assertNull(cache.get("/b/a.cbz", 7))
        cache.load()
        assertTrue(cache.isLoaded)
        assertEquals(BookFacts(-1, 0), cache.get("/b/a.cbz", 7))
    }
}
