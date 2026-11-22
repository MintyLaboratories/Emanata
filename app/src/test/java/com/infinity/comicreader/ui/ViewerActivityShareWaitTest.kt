package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.RemoteChannel
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareSpeed
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.testing.Waits
import com.infinity.comicreader.testing.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random

/** A share slow to answer, as one waking its disk: the reader says so after a while, and no longer once data arrives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerActivityShareWaitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fake: FakeRemoteFs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
        val share = tmp.newFolder("share")
        // Pages of noise, so each is read from the share apart from the others.
        val pages = (0 until PAGES).map { i -> Fixtures.entry("Tale 001-%03d.png".format(i), noise(300, 450, i)) }
        Fixtures.zip(File(share, "Tale 001.cbz"), *pages.toTypedArray())
        // Held until the test opens the hold: only a hang ends it.
        fake = FakeRemoteFs(share).apply { slowCapMs = Waits.LIVENESS_MS }
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        // As on a device: the main thread never waits on the share.
        Remotes.onMainThread = { Looper.getMainLooper().thread === Thread.currentThread() }
        // Silence on the share is timed by the clock the looper idles.
        Remotes.clock = { SystemClock.uptimeMillis() }
    }

    @After
    fun tearDown() {
        fake.slow?.countDown()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        Remotes.clock = { System.nanoTime() / 1_000_000 }
        RemoteChannel.thrifty = false
    }

    private fun noise(w: Int, h: Int, seed: Int): ByteArray {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(IntArray(w * h) { (0xFF shl 24) or random.nextInt(0x1000000) }, w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun launch(): ViewerActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, "/net/s1/Tale 001.cbz")
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
    }

    /**
     * Battery saver on: nothing is fetched ahead from the share and no page sizes or seams
     * are scanned there, so the share reads only what the reader shows ([holdLaterPages]).
     */
    private fun saveBattery() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>().getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
    }

    /** Makes the share slow to read the book's later pages; once [settled], only what the test asks for next reads them. */
    private fun holdLaterPages(): CountDownLatch {
        val size = File(tmp.root, "share/Tale 001.cbz").length()
        fake.slowReads = size * 3 / 5 until size - 256 * 1024
        return CountDownLatch(1).also { fake.slow = it }
    }

    /** Waits until the share holds a request, as one waking its disk. */
    private fun awaitHeld() = idleUntil("the share holding a request") { fake.held.get() > 0 }

    /** True once the first page showed and the scans it holds back were let go ([ViewerActivity.openSettled] counts them only then). */
    private fun ViewerActivity.scansReleased() =
        ViewerActivity::class.java.getDeclaredField("scansReleased").apply { isAccessible = true }.getBoolean(this)

    /**
     * Waits until the book is open, its first page shows, its scans were let go, and
     * nothing the open started still runs: no decode, no auto-crop measure, no job.
     * The reader then asks the share for nothing more on its own.
     */
    private fun ViewerActivity.settled() = idleUntil("the first page shown, its scans let go, all the open started done") {
        progress().visibility != View.VISIBLE && scansReleased() && openSettled() &&
            pageAdapter?.let { !it.blankAt(pages().currentItem) && it.measures == 0 } == true
    }

    private fun ViewerActivity.note() = findViewById<View>(R.id.viewer_share_wait)

    private fun ViewerActivity.progress() = findViewById<View>(R.id.viewer_progress)

    private fun ViewerActivity.pages() = findViewById<ViewPager2>(R.id.viewer_pager)

    @Test
    fun onAShareWhereReadAheadIsSkippedNoPageIsDecodedAhead() {
        fun ahead(speed: ShareSpeed): Int {
            Remotes.serve(listOf(FakeRemoteFs.share().copy(speed = speed))) { fake }
            val viewer = launch()
            idleUntil("the pages laid out") { viewer.pages().adapter != null }
            val adapter = viewer.pages().adapter as PageAdapter
            return PageAdapter::class.java.getDeclaredField("preload").apply { isAccessible = true }.getInt(adapter)
        }
        assertEquals(0, ahead(ShareSpeed.SLOW))
        assertTrue(ahead(ShareSpeed.FAST) > 0)
    }

    @Test
    fun aBookOpeningSlowlySaysSoAfterTwoSecondsUntilItOpens() {
        val held = CountDownLatch(1)
        fake.slow = held
        val viewer = launch()
        awaitHeld()
        idleFor(1_500)
        assertEquals(View.VISIBLE, viewer.progress().visibility)
        assertEquals(View.GONE, viewer.note().visibility)
        idleFor(600)
        assertEquals(View.VISIBLE, viewer.note().visibility)
        // Waking the disk takes long, but fails nothing.
        idleFor(15_000)
        assertEquals(View.VISIBLE, viewer.note().visibility)
        fake.slow = null
        held.countDown()
        idleUntil("the book open") { viewer.progress().visibility != View.VISIBLE }
        assertEquals(View.GONE, viewer.note().visibility)
        assertFalse(viewer.isFinishing)
        assertEquals(PAGES, viewer.pages().adapter!!.itemCount)
    }

    @Test
    fun aPageLoadingSlowlySaysSoUntilItShows() {
        saveBattery()
        val viewer = launch()
        viewer.settled()
        val held = holdLaterPages()
        viewer.pages().setCurrentItem(PAGES - 2, false)
        awaitHeld()
        idleFor(1_500)
        assertEquals(View.GONE, viewer.note().visibility)
        idleFor(600)
        assertEquals(View.VISIBLE, viewer.note().visibility)
        fake.slow = null
        held.countDown()
        idleUntil("the note gone") { viewer.note().visibility != View.VISIBLE }
        assertEquals(View.GONE, viewer.note().visibility)
        assertFalse(viewer.isFinishing)
    }

    @Test
    fun otherWorkOnTheShareAnsweringNeverHidesTheWait() {
        File(tmp.root, "share/notes.txt").writeText("cover scan")
        saveBattery()
        val viewer = launch()
        viewer.settled()
        val held = holdLaterPages()
        viewer.pages().setCurrentItem(PAGES - 2, false)
        awaitHeld()
        idleFor(1_500)
        // A cover scan reading another file of the same share, answered at once.
        thread { Remotes.open("/net/s1/notes.txt").use { it.read(0, ByteArray(4), 0, 4) } }.join()
        idleFor(600)
        assertEquals(View.VISIBLE, viewer.note().visibility)
        assertEquals(View.VISIBLE, viewer.findViewById<View>(R.id.viewer_share_wait_spinner).visibility)
        fake.slow = null
        held.countDown()
        idleUntil("the note gone") { viewer.note().visibility != View.VISIBLE }
        assertFalse(viewer.isFinishing)
    }

    @Test
    fun aPageThatShowsAtOnceSaysNothing() {
        val viewer = launch()
        viewer.settled()
        idleFor(3_000)
        assertEquals(View.GONE, viewer.note().visibility)
    }

    @Test
    fun twoPagesCroppedTogetherSayWhileMeasuredSlowly() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.twoPage = TwoPage.ON
        prefs.autoCrop = true
        saveBattery()
        val viewer = launch()
        viewer.settled()
        val held = holdLaterPages()
        // A pair far ahead: its shared crop measures both pages from the share first.
        val pages = viewer.pages()
        pages.setCurrentItem(pages.adapter!!.itemCount - 2, false)
        awaitHeld()
        idleFor(2_100)
        assertEquals(View.VISIBLE, viewer.note().visibility)
        fake.slow = null
        held.countDown()
        idleUntil("the note gone") { viewer.note().visibility != View.VISIBLE }
        assertEquals(View.GONE, viewer.note().visibility)
        assertFalse(viewer.isFinishing)
    }

    private companion object {
        const val PAGES = 16
    }
}
