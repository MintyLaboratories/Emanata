package com.infinity.comicreader

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.BookFactsCache
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.LikenessStore
import com.infinity.comicreader.testing.awaitUntil
import com.infinity.comicreader.testing.joined
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
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LazyApplication(LazyApplication.LazyLoad.ON)
class AppTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun endWaitsForEachStartWork() {
        val reached = Collections.synchronizedMap(HashMap<String, Thread>())
        val joinedBy = Collections.synchronizedSet(HashSet<String>())
        val abandon = AtomicBoolean(false)
        // Holds each start work until something joins its thread: created lazily, the app starts after this.
        App.startGate = { name ->
            val me = Thread.currentThread()
            reached[name] = me
            awaitUntil("the app's end to wait for $name") { joined(me) || abandon.get() }
            if (!abandon.get()) joinedBy += name
        }
        try {
            val app = ApplicationProvider.getApplicationContext<App>()
            awaitUntil("all start work to reach the gate") { reached.keys == START_WORK }
            app.onTerminate()
            // A start work the end did not join is still held here.
            assertEquals(START_WORK, joinedBy.toSet())
        } finally {
            abandon.set(true)
            App.startGate = {}
            synchronized(reached) { reached.values.toList() }.forEach { awaitUntil("${it.name} to end") { !it.isAlive } }
        }
    }

    @Test
    fun endClosesTheAppDatabases() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val book = tmp.newFile("a.cbz").apply { writeBytes(Random(1).nextBytes(4096)) }
        BookIds.of(book)
        BookFactsCache(app).load()
        LikenessStore(app).get(BookId(1))
        val dir = app.getDatabasePath("x").parent!!
        assertTrue(openDatabases(dir).containsAll(DATABASES.map { app.getDatabasePath(it).path }))
        app.onTerminate()
        assertEquals(emptyList<String>(), openDatabases(dir))
    }

    @Test
    fun endLetsGoOfWhatTheIndexKept() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val book = tmp.newFile("a.cbz").apply { writeBytes(Random(1).nextBytes(4096)) }
        assertEquals(BookIds.of(book), BookIds.known(book))
        app.onTerminate()
        assertTrue(app.deleteDatabase("book_ids.db"))
        // Opened again, the index knows only what is on disk now.
        BookIds.init(app)
        assertNull(BookIds.known(book))
    }

    /** Paths of the SQLite databases in [dir] open now. */
    private fun openDatabases(dir: String): List<String> {
        val field = SQLiteDatabase::class.java.getDeclaredField("sActiveDatabases").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val active = field.get(null) as WeakHashMap<SQLiteDatabase, Any?>
        return synchronized(active) { active.keys.toList() }.filter { it.isOpen && File(it.path).parent == dir }.map { it.path }
    }

    private companion object {
        /** The names [App] gives its start work's threads. */
        val START_WORK = setOf("calibration-load", "book-index", "cover-detector", "cache-cleanup", "model-tidy")

        /** The files of the databases the app keeps open while it runs. */
        val DATABASES = listOf("book_ids.db", "book_facts.db", "likeness.db")
    }
}
