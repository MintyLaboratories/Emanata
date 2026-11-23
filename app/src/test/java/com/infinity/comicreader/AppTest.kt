package com.infinity.comicreader

import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.testing.awaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LazyApplication(LazyApplication.LazyLoad.ON)
class AppTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun endWaitsForTheStartWork() {
        val before = Thumbs.detector
        val asked = CountDownLatch(1)
        val held = CountDownLatch(1)
        val answered = AtomicBoolean(false)
        // Holds the start's cover-detector work until released: created lazily, the app starts after this.
        Thumbs.detector = { context ->
            asked.countDown()
            held.await()
            before(context).also { answered.set(true) }
        }
        var ending: Thread? = null
        try {
            val app = ApplicationProvider.getApplicationContext<App>()
            awaitUntil("the start work to ask for the cover detector") { asked.count == 0L }
            val end = thread(name = "app-end") { app.onTerminate() }
            ending = end
            awaitUntil("the app's end to wait or return") { end.state == Thread.State.WAITING || !end.isAlive }
            assertTrue("the app ended while its start work still ran", end.isAlive)
            held.countDown()
            awaitUntil("the app's end to return") { !end.isAlive }
            assertTrue(answered.get())
        } finally {
            held.countDown()
            ending?.join()
            Thumbs.detector = before
        }
    }

    @Test
    fun endLetsGoOfTheBookIndex() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val book = tmp.newFile("a.cbz").apply { writeBytes(Random(1).nextBytes(4096)) }
        val id = BookIds.of(book)
        app.onTerminate()
        assertNull(BookIds.known(book.absolutePath, book.length(), book.lastModified()))
        BookIds.init(app)
        assertEquals(id, BookIds.known(book))
    }
}
