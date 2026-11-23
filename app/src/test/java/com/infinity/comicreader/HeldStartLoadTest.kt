package com.infinity.comicreader

import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.BookIndex
import com.infinity.comicreader.testing.awaitUntil
import com.infinity.comicreader.testing.joined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * A test ends while its app's start load holds the book index open, mid-read; the next
 * test, in the same sandbox (the app's statics live on), must find that load ended with
 * its test, and look up IDs through an index of its own app. Robolectric drops every
 * SQLite connection between tests: a load that ran on into the next test once failed
 * there with "Illegal connection pointer". The tests run in name order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LazyApplication(LazyApplication.LazyLoad.ON)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HeldStartLoadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aTestEndsWhileItsStartLoadIsHeld() {
        val once = AtomicBoolean(false)
        // Holds the first load, the start's, with the database open, until the app's end waits for it or the next test begins.
        BookIndex.reading = {
            if (once.compareAndSet(false, true)) {
                val me = Thread.currentThread()
                held = me
                awaitUntil("the app's end to wait for its start load, or the next test to begin") { joined(me) || nextBegan }
            }
        }
        try {
            ApplicationProvider.getApplicationContext<App>()
            awaitUntil("the start load to reach the gate") { held != null }
        } finally {
            BookIndex.reading = {}
        }
    }

    @Test
    fun bNextTestFindsThatLoadEnded() {
        val load = checkNotNull(held) { "runs right after aTestEndsWhileItsStartLoadIsHeld, in one run of this class" }
        val ranOn = load.isAlive
        nextBegan = true
        awaitUntil("the earlier test's start load to end") { !load.isAlive }
        held = null
        ApplicationProvider.getApplicationContext<App>()
        val book = tmp.newFile("a.cbz").apply { writeBytes(Random(2).nextBytes(4096)) }
        val lookup = runCatching { BookIds.of(book) }
        assertFalse("the earlier test's start load ran on into this test; a lookup here: $lookup", ranOn)
        assertEquals(lookup.getOrThrow(), BookIds.known(book))
    }

    private companion object {
        /** The thread of the start load held by the first test. */
        @Volatile
        var held: Thread? = null

        /** Set once the second test begins. */
        @Volatile
        var nextBegan = false
    }
}
