package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        context.getSharedPreferences("book_facts", Context.MODE_PRIVATE).edit().clear().commit()
        BookFactsCache.reset()
    }

    @Test
    fun factsAreKeptPerSizeAndAcrossInstances() {
        BookFactsCache(context).putAll(mapOf("/b/a.cbz" to (100L to BookFacts(24, 2014))))
        assertEquals(BookFacts(24, 2014), BookFactsCache(context).get("/b/a.cbz", 100))
        // A different size means the book changed.
        assertNull(BookFactsCache(context).get("/b/a.cbz", 101))
        assertNull(BookFactsCache(context).get("/b/other.cbz", 100))
    }

    @Test
    fun factsSurviveARestart() {
        BookFactsCache(context).putAll(mapOf("/b/a.cbz" to (7L to BookFacts(-1, 0))))
        BookFactsCache.reset()
        assertEquals(BookFacts(-1, 0), BookFactsCache(context).get("/b/a.cbz", 7))
    }

    @Test
    fun encodingRoundTripsAndRejectsJunk() {
        assertEquals(5L to BookFacts(12, 1999), BookFactsCache.decode(BookFactsCache.encode(5, BookFacts(12, 1999))))
        assertNull(BookFactsCache.decode("5:x:1999"))
        assertNull(BookFactsCache.decode("5:12"))
        assertNull(BookFactsCache.decode(null))
    }
}
