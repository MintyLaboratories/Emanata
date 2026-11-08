package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.reader.DirectionDetection
import com.infinity.comicreader.reader.Slices
import com.infinity.comicreader.reader.Wraparound
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

    private val a = BookId(0x0a)
    private val b = BookId(0x0b)
    private val c = BookId(0x0c)

    @Test
    fun factsAreKeptPerBookAndAcrossInstances() {
        BookFactsCache(context).put(a, BookFacts(24, 2014))
        assertEquals(BookFacts(24, 2014), BookFactsCache(context).get(a))
        // A changed book has another ID.
        assertNull(BookFactsCache(context).get(b))
        assertNull(BookFactsCache(context).get(null))
        BookFactsCache(context).put(a, BookFacts(25, 2014))
        assertEquals(BookFacts(25, 2014), BookFactsCache(context).get(a))
    }

    @Test
    fun removedFactsAreGoneForGood() {
        BookFactsCache(context).put(a, BookFacts(24, 2014))
        BookFactsCache(context).put(b, BookFacts(12, 2014))
        BookFactsCache(context).remove(a)
        assertNull(BookFactsCache(context).get(a))
        BookFactsCache.reset()
        val read = BookFactsCache(context).apply { load() }
        assertNull(read.get(a))
        assertEquals(BookFacts(12, 2014), read.get(b))
    }

    @Test
    fun detectedDirectionIsKept() {
        val detected = DirectionDetection.Detected(ReadingDirection.RTL, DirectionDetection.Source.PUBLISHER)
        BookFactsCache(context).put(BookId(-5), BookFacts(20, 0, detected))
        BookFactsCache.reset()
        val cache = BookFactsCache(context).apply { load() }
        assertEquals(BookFacts(20, 0, detected), cache.get(BookId(-5)))
    }

    @Test
    fun slicesAreKeptUncheckedNoneOrFound() {
        val seams = Slices.Checked(DirectionDetection.Detected(ReadingDirection.TTB, DirectionDetection.Source.SEAMS))
        val cache = BookFactsCache(context)
        cache.put(a, BookFacts(5, 0))
        cache.put(b, BookFacts(5, 0, slices = Slices.Checked(null)))
        cache.put(c, BookFacts(5, 0, slices = seams))
        BookFactsCache.reset()
        val read = BookFactsCache(context).apply { load() }
        assertNull(read.get(a)?.slices)
        assertEquals(Slices.Checked(null), read.get(b)?.slices)
        assertEquals(seams, read.get(c)?.slices)
    }

    @Test
    fun factsReadAgainKeepTheSlicesFoundBefore() {
        val seams = Slices.Checked(DirectionDetection.Detected(ReadingDirection.TTB, DirectionDetection.Source.SEAMS))
        val cache = BookFactsCache(context)
        cache.put(a, BookFacts(5, 0, slices = seams))
        cache.put(a, BookFacts(6, 2020))
        assertEquals(BookFacts(6, 2020, slices = seams), cache.get(a))
        cache.put(a, BookFacts(6, 2020, slices = Slices.Checked(null)))
        assertEquals(Slices.Checked(null), cache.get(a)?.slices)
    }

    @Test
    fun slicesAreKeptOnlyWithFacts() {
        val seams = Slices.Checked(DirectionDetection.Detected(ReadingDirection.TTB, DirectionDetection.Source.SEAMS))
        val cache = BookFactsCache(context)
        cache.keepSlices(a, seams)
        assertNull(cache.get(a))
        cache.put(a, BookFacts(5, 0))
        cache.keepSlices(a, seams)
        BookFactsCache.reset()
        assertEquals(BookFacts(5, 0, slices = seams), BookFactsCache(context).apply { load() }.get(a))
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
        assertNull(cache.get(a))
        cache.put(a, BookFacts(12, 2001))
        assertEquals(BookFacts(12, 2001), cache.get(a))
    }

    @Test
    fun factsSurviveARestartOnceLoaded() {
        BookFactsCache(context).put(a, BookFacts(-1, 0))
        BookFactsCache.reset()
        val cache = BookFactsCache(context)
        // Looking up never reads the disk.
        assertFalse(cache.isLoaded)
        assertNull(cache.get(a))
        cache.load()
        assertTrue(cache.isLoaded)
        assertEquals(BookFacts(-1, 0), cache.get(a))
    }

    @Test
    fun coversAreKeptWithFactsAndThroughRereads() {
        val cache = BookFactsCache(context)
        cache.keepCover(a, CoverFront(0, Wraparound.Half.RIGHT))
        assertNull(cache.get(a))
        cache.put(a, BookFacts(5, 0))
        cache.put(b, BookFacts(5, 0))
        cache.keepCover(a, CoverFront(0, Wraparound.Half.RIGHT))
        cache.keepCover(b, CoverFront(2, null))
        // Facts read again do not say what the cover told.
        cache.put(a, BookFacts(6, 2020))
        BookFactsCache.reset()
        val read = BookFactsCache(context).apply { load() }
        assertEquals(BookFacts(6, 2020, cover = CoverFront(0, Wraparound.Half.RIGHT)), read.get(a))
        assertEquals(CoverFront(2, null), read.get(b)?.cover)
        assertNull(read.get(c))
    }
}
