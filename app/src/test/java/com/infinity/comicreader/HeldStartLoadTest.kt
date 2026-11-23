package com.infinity.comicreader

import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.BookIndex
import com.infinity.comicreader.testing.awaitUntil
import com.infinity.comicreader.testing.joined
import com.infinity.comicreader.testing.openDatabases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
        val path = AtomicReference<String>()
        // Holds the first load, the start's, with the database open, until the app's end waits for it or the next test begins.
        BookIndex.reading = {
            if (once.compareAndSet(false, true)) {
                val me = Thread.currentThread()
                held = me
                awaitUntil("the app's end to wait for its start load, or the next test to begin") { joined(me) || nextBegan }
                indexOpenWhenReleased = checkNotNull(path.get()).let { it in openDatabases(File(it).parent!!) }
            }
        }
        try {
            // Read only once the gate lets go, which is after this test's body.
            path.set(ApplicationProvider.getApplicationContext<App>().getDatabasePath("book_ids.db").path)
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
        val indexWasOpen = indexOpenWhenReleased
        held = null
        indexOpenWhenReleased = null
        assertTrue("the app's end closed the index before its start load ended", indexWasOpen == true)
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

        /** Whether the index's database was still open when the held load was let go; null until then. */
        @Volatile
        var indexOpenWhenReleased: Boolean? = null

        /** Set once the second test begins. */
        @Volatile
        var nextBegan = false
    }
}
