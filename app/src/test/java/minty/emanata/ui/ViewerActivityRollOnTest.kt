package minty.emanata.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.ai.ChainKind
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.data.ResumeMode
import minty.emanata.net.FakeRemoteFs
import minty.emanata.net.Remotes
import minty.emanata.net.ShareSpeed
import minty.emanata.reader.TestImages
import minty.emanata.testing.bookId
import minty.emanata.testing.idleUntil
import minty.emanata.testing.ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Rolling a strip on into the next chapter keeps each chapter's data with its own Book ID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityRollOnTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var library: Library
    private lateinit var first: File
    private lateinit var second: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).apply {
            pageMode = "continuous"
            readingDir = "ttb"
            resumeMode = ResumeMode.NO
        }
        library = Library(context)
        val dir = tmp.newFolder("books")
        first = book(dir, "a.cbz", pages = 3, seed = 1)
        second = book(dir, "b.cbz", pages = 3, seed = 2)
    }

    /** A chapter of [pages] far taller than the screen in a strip; [seed] makes its bytes its own. */
    private fun book(dir: File, name: String, pages: Int, seed: Int): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zip ->
            repeat(pages) {
                zip.putNextEntry(ZipEntry("p%02d.png".format(it)))
                zip.write(TestImages.png(10 + seed, 80))
                zip.closeEntry()
            }
        }
        return f
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private lateinit var controller: ActivityController<ViewerActivity>

    private fun launch(file: File): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
        controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        val viewer = controller.get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    private fun ViewerActivity.strip(): RecyclerView = findViewById(R.id.viewer_vertical)

    private fun ViewerActivity.openPath(): String? = intent.getStringExtra(ViewerActivity.EXTRA_PATH)

    /** Scrolls the strip down half a screen at a time until [done], named [what]. */
    private fun ViewerActivity.scrollUntil(what: String, done: () -> Boolean) {
        val strip = strip()
        idleUntil(what, step = { strip.scrollBy(0, strip.height / 2); idle() }, done = done)
    }

    /** Opens [first], waits for [second] to be laid in behind it and scrolls on into it. */
    private fun rollOn(): ViewerActivity {
        val viewer = launch(first)
        idleUntil("the second book laid in behind") { (viewer.strip().adapter?.itemCount ?: 0) >= 6 }
        assertEquals(6, viewer.strip().adapter?.itemCount)
        viewer.scrollUntil("the second book open") { viewer.openPath() == second.absolutePath }
        assertEquals(second.absolutePath, viewer.openPath())
        return viewer
    }

    @Test
    fun theNextChaptersProgressIsItsOwn() {
        val a = bookId(first)
        val b = bookId(second)
        assertNotEquals(a, b)
        rollOn()
        controller.pause().stop()
        assertEquals(2, library.historyFor(a)?.page)
        assertEquals(first.absolutePath, library.historyFor(a)?.path)
        assertEquals(second.absolutePath, library.historyFor(b)?.path)
        assertEquals(first.absolutePath, library.pathOf(a))
        assertEquals(second.absolutePath, library.pathOf(b))
    }

    @Test
    fun reachingTheNextChaptersEndMarksItRead() {
        val b = bookId(second)
        val viewer = rollOn()
        assertFalse(library.isRead(b))
        viewer.scrollUntil("the book read") { library.isRead(b) }
        assertTrue(library.isRead(b))
    }

    @Test
    fun aBookmarkInTheNextChapterIsItsOwn() {
        val a = bookId(first)
        val b = bookId(second)
        val viewer = rollOn()
        shadowOf(viewer).clickMenuItem(R.id.menu_bookmark)
        assertTrue(library.bookmarksFor(a).isEmpty())
        assertEquals(listOf(second.absolutePath), library.bookmarksFor(b).map { it.path })
    }

    @Test
    fun theNextChapterRunsItsOwnDefaultChain() {
        library.setChainKind(ref(second), ChainKind.MANGA)
        val viewer = launch(first)
        idleUntil("the second book laid in behind") { (viewer.strip().adapter?.itemCount ?: 0) >= 6 }
        assertEquals(ChainKind.OTHER, viewer.aiKindRun())
        assertEquals(ChainKind.MANGA, viewer.aiKindRun(ahead = true))
        viewer.scrollUntil("the second book open") { viewer.openPath() == second.absolutePath }
        assertEquals(ChainKind.MANGA, viewer.aiKindRun())
    }

    /** Opens the first book from a share set to [speed], waits until it is [ready] (named [what]), and returns the strip's page count. */
    private fun fromShare(speed: ShareSpeed, what: String, ready: ViewerActivity.() -> Boolean): Int {
        Remotes.serve(listOf(FakeRemoteFs.share().copy(speed = speed))) { FakeRemoteFs(first.parentFile!!) }
        try {
            val viewer = launch(Remotes.file("/net/s1/a.cbz"))
            idleUntil(what) { viewer.ready() }
            return viewer.strip().adapter?.itemCount ?: 0
        } finally {
            controller.pause().stop().destroy()
            Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        }
    }

    @Test
    fun onAShareWhereReadAheadIsSkippedTheNextChapterOpensOnlyWhenReached() {
        // Read-ahead is decided as the open lays the book out: once all the open started is done, none comes.
        assertEquals(3, fromShare(ShareSpeed.SLOW, "all the open started done") { openSettled() })
        assertEquals(6, fromShare(ShareSpeed.FAST, "the second book laid in behind") { (strip().adapter?.itemCount ?: 0) >= 6 })
    }
}
